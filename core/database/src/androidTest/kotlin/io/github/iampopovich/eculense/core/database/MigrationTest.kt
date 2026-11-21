package io.github.iampopovich.eculense.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), EcuDatabase::class.java)

    @Test
    fun v1DataSurvivesAndBelongsToDefaultCar() = runTest {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL("INSERT INTO sessions (id, name, startedAtMs, endedAtMs, source, vin, externalId) VALUES (1, 'demo', 10, 20, 'LIVE', NULL, NULL)")
            db.execSQL("INSERT INTO sessions (id, name, startedAtMs, endedAtMs, source, vin, externalId) VALUES (2, 'cs', 30, 40, 'IMPORT_CARSCANNER', NULL, 'carscanner:a.brc')")
            db.execSQL("INSERT INTO readings (sessionId, sensorId, value, unit, timestampMs) VALUES (1, '01.0C', 800.0, 'rpm', 11)")
            db.execSQL("INSERT INTO readings (sessionId, sensorId, value, unit, timestampMs) VALUES (2, '01.05', 90.0, '°C', 31)")
            db.execSQL("INSERT INTO dtc_snapshots (takenAtMs, vin, code, kind) VALUES (35, NULL, 'P0113', 'STORED')")
        }

        helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT id, name FROM cars").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals(EcuDatabase.DEFAULT_CAR_ID, c.getLong(0))
                assertEquals(EcuDatabase.DEFAULT_CAR_NAME, c.getString(1))
            }
            db.query("SELECT COUNT(*) FROM sessions WHERE carId = 1").use { c -> c.moveToFirst(); assertEquals(2, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM readings").use { c -> c.moveToFirst(); assertEquals(2, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM dtc_snapshots WHERE carId = 1").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }

        // Room opens the migrated database without schema errors, DAOs see the data
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), EcuDatabase::class.java, dbName)
            .addMigrations(*EcuDatabase.MIGRATIONS).build()
        try {
            assertEquals(2, room.sessionDao().observeSessions().first().size)
            assertEquals(listOf(EcuDatabase.DEFAULT_CAR_NAME), room.carDao().all().map { it.name })
            assertEquals(1, room.sessionDao().readings(1).size)
        } finally {
            room.close()
        }
    }

    @Test
    fun v2DataSurvivesMigrationToFindings() = runTest {
        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL("INSERT INTO cars (id, name, brand, year) VALUES (1, 'Тестовая', 'Kia', 2005)")
            db.execSQL("INSERT INTO sessions (id, name, startedAtMs, endedAtMs, source, vin, externalId, carId) VALUES (1, 'cs', 30, 40, 'IMPORT_CARSCANNER', NULL, 'carscanner:a.brc', 1)")
            db.execSQL("INSERT INTO readings (sessionId, sensorId, value, unit, timestampMs) VALUES (1, '01.05', 90.0, '°C', 31)")
            db.execSQL("INSERT INTO readings (sessionId, sensorId, value, unit, timestampMs) VALUES (1, '01.07', 12.5, '%', 32)")
            db.execSQL("INSERT INTO dtc_snapshots (takenAtMs, vin, code, kind, carId) VALUES (35, NULL, 'P0113', 'STORED', 1)")
            db.execSQL(
                "INSERT INTO trips (carId, startedAtMs, endedAtMs, distanceMm, fuelUl, fuelPricePerL, drivingMs, standingMs, source, externalId) " +
                    "VALUES (1, 1, 2, 3, 4, NULL, 1, 0, 'IMPORT_CARSCANNER', 'carscanner:1:1')",
            )
        }

        helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3).use { db ->
            fun count(sql: String) = db.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(1, count("SELECT COUNT(*) FROM sessions"))
            assertEquals(2, count("SELECT COUNT(*) FROM readings"))
            assertEquals(1, count("SELECT COUNT(*) FROM dtc_snapshots"))
            assertEquals(1, count("SELECT COUNT(*) FROM trips"))
            assertEquals(1, count("SELECT COUNT(*) FROM cars WHERE name = 'Тестовая'"))
            assertEquals(0, count("SELECT COUNT(*) FROM findings"))
        }

        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), EcuDatabase::class.java, dbName)
            .addMigrations(*EcuDatabase.MIGRATIONS).build()
        try {
            val f = FindingEntity(carId = 1, ruleId = "r", severity = "WARNING", title = "t", message = "m", evidenceJson = "{}", periodFromMs = null, periodToMs = null, createdAtMs = 1)
            room.findingDao().replaceForCar(1, listOf(f, f.copy(ruleId = "r2")))
            room.findingDao().replaceForCar(1, listOf(f.copy(ruleId = "r3")))
            assertEquals(listOf("r3"), room.findingDao().forCar(1).map { it.ruleId })
            assertEquals(2, room.sessionDao().readings(1).size)
            // findings are deleted together with the car
            room.carDao().insert(CarEntity(name = "второй"))
            room.carDao().delete(1)
            assertEquals(0, room.findingDao().forCar(1).size)
        } finally {
            room.close()
        }
    }

    @Test
    fun v7DtcSnapshotsKeepRowsAndGetFreezeFrameAndReadinessTable() = runTest {
        helper.createDatabase(dbName, 7).use { db ->
            db.execSQL("INSERT INTO cars (id, name) VALUES (1, 'Тестовая')")
            db.execSQL("INSERT INTO sessions (id, name, startedAtMs, endedAtMs, source, vin, externalId, carId) VALUES (1, 'live', 30, 40, 'LIVE', NULL, NULL, 1)")
            db.execSQL("INSERT INTO readings (sessionId, sensorId, value, unit, timestampMs) VALUES (1, '01.0C', 800.0, 'rpm', 31)")
            db.execSQL("INSERT INTO dtc_snapshots (takenAtMs, vin, code, kind, carId) VALUES (35, NULL, 'P0133', 'STORED', 1)")
        }
        helper.runMigrationsAndValidate(dbName, 8, true, MIGRATION_7_8).use { db ->
            fun count(sql: String) = db.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(1, count("SELECT COUNT(*) FROM readings"))
            assertEquals(1, count("SELECT COUNT(*) FROM dtc_snapshots WHERE code = 'P0133' AND freezeFrame IS NULL"))
            assertEquals(0, count("SELECT COUNT(*) FROM readiness_snapshots"))
        }
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), EcuDatabase::class.java, dbName)
            .addMigrations(*EcuDatabase.MIGRATIONS).build()
        try {
            val dao = room.dtcDao()
            dao.insert(listOf(DtcSnapshotEntity(takenAtMs = 50, vin = null, code = "P0300", kind = "STORED", carId = 1, freezeFrame = "dtc=P0300;01.0C=812.5|rpm")))
            assertEquals("dtc=P0300;01.0C=812.5|rpm", dao.forCar(1).last().freezeFrame)
            dao.insertReadiness(ReadinessSnapshotEntity(carId = 1, takenAtMs = 60, reason = "BEFORE_CLEAR", milOn = true, dtcCount = 2, monitors = "CATALYST=COMPLETE", counters = "warmUps=12"))
            assertEquals("BEFORE_CLEAR", dao.readinessForCar(1, 5).single().reason)
        } finally {
            room.close()
        }
    }

    @Test
    fun v3CarGetsDriveAndMassColumnsKeepingData() = runTest {
        helper.createDatabase(dbName, 3).use { db ->
            db.execSQL("INSERT INTO cars (id, name, brand, year) VALUES (1, 'Тестовая', 'Kia', 2005)")
            db.execSQL("INSERT INTO sessions (id, name, startedAtMs, endedAtMs, source, vin, externalId, carId) VALUES (1, 'cs', 30, 40, 'IMPORT_CARSCANNER', NULL, 'carscanner:a.brc', 1)")
            db.execSQL("INSERT INTO readings (sessionId, sensorId, value, unit, timestampMs) VALUES (1, '01.05', 90.0, '°C', 31)")
        }
        helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_3_4).use { db ->
            fun count(sql: String) = db.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(1, count("SELECT COUNT(*) FROM readings"))
            assertEquals(1, count("SELECT COUNT(*) FROM cars WHERE drive IS NULL AND curbWeightKg IS NULL AND brand = 'Kia'"))
        }
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), EcuDatabase::class.java, dbName)
            .addMigrations(*EcuDatabase.MIGRATIONS).build()
        try {
            val car = room.carDao().all().single()
            room.carDao().update(car.copy(drive = "ALL", curbWeightKg = 1917))
            assertEquals("ALL" to 1917, room.carDao().all().single().let { it.drive to it.curbWeightKg })
        } finally {
            room.close()
        }
    }

    @Test
    fun v4OdometerMovesToLogAndMaintenanceTablesAppear() = runTest {
        helper.createDatabase(dbName, 4).use { db ->
            db.execSQL("INSERT INTO cars (id, name, brand, odometerKm, odometerAtMs) VALUES (1, 'Тестовая', 'Kia', 120000, 1000)")
            db.execSQL("INSERT INTO cars (id, name, odometerKm, odometerAtMs) VALUES (2, 'Вторая', 5000, NULL)")
            db.execSQL("INSERT INTO cars (id, name) VALUES (3, 'Третья')")
            db.execSQL("INSERT INTO sessions (id, name, startedAtMs, endedAtMs, source, vin, externalId, carId) VALUES (1, 'live', 30, 40, 'LIVE', NULL, NULL, 1)")
            db.execSQL("INSERT INTO readings (sessionId, sensorId, value, unit, timestampMs) VALUES (1, '01.0D', 40.0, 'km/h', 31)")
            db.execSQL(
                "INSERT INTO trips (carId, startedAtMs, endedAtMs, distanceMm, fuelUl, fuelPricePerL, drivingMs, standingMs, source, externalId) " +
                    "VALUES (1, 1, 2, 3, 4, NULL, 1, 0, 'IMPORT_CARSCANNER', 'carscanner:1:1')",
            )
        }
        val before = System.currentTimeMillis()
        helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5).use { db ->
            fun count(sql: String) = db.query(sql).use { c -> c.moveToFirst(); c.getLong(0) }
            assertEquals(1, count("SELECT COUNT(*) FROM readings"))
            assertEquals(1, count("SELECT COUNT(*) FROM trips WHERE fuelMethod IS NULL"))
            assertEquals(0, count("SELECT COUNT(*) FROM maintenance_items"))
            assertEquals(2, count("SELECT COUNT(*) FROM odometer_log WHERE source = 'MANUAL'"))
            assertEquals(120000, count("SELECT km FROM odometer_log WHERE carId = 1"))
            assertEquals(1000, count("SELECT atMs FROM odometer_log WHERE carId = 1"))
            // no date in the profile — migration date
            assertTrue(count("SELECT atMs FROM odometer_log WHERE carId = 2") >= before / 1000 * 1000)
            assertEquals(0, count("SELECT COUNT(*) FROM cars WHERE odometerKm IS NOT NULL OR odometerAtMs IS NOT NULL"))
        }
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), EcuDatabase::class.java, dbName)
            .addMigrations(*EcuDatabase.MIGRATIONS).build()
        try {
            assertEquals(listOf(120000L, 5000L), room.odometerDao().observeLatestManual().first().sortedBy { it.carId }.map { it.km })
            val id = room.maintenanceDao().insert(MaintenanceItemEntity(carId = 1, title = "Масло", intervalKm = 10000, intervalMonths = 12, lastDoneKm = null, lastDoneAtMs = null, note = null))
            room.maintenanceDao().update(room.maintenanceDao().byId(id)!!.copy(lastDoneKm = 119000, lastDoneAtMs = 900))
            assertEquals(119000L, room.maintenanceDao().forCar(1).single().lastDoneKm)
            // a live recording without its own trip — a trip candidate
            assertEquals(listOf(1L), room.tripDao().sessionsWithoutTrip().map { it.id })
            // maintenance and mileage are deleted together with the car
            room.carDao().delete(1)
            assertEquals(0, room.maintenanceDao().forCar(1).size)
            assertEquals(0, room.odometerDao().forCar(1).size)
        } finally {
            room.close()
        }
    }

    @Test
    fun v5DataSurvivesAndImportTablesAppear() = runTest {
        helper.createDatabase(dbName, 5).use { db ->
            db.execSQL("INSERT INTO cars (id, name, brand, externalId) VALUES (1, 'Тестовая', 'Lada', 'carscanner:7')")
            db.execSQL("INSERT INTO sessions (id, name, startedAtMs, endedAtMs, source, vin, externalId, carId) VALUES (1, 'cs', 30, 40, 'IMPORT_CARSCANNER', NULL, 'carscanner:a.brc', 1)")
            db.execSQL("INSERT INTO readings (sessionId, sensorId, value, unit, timestampMs) VALUES (1, '01.05', 90.0, '°C', 31)")
            db.execSQL(
                "INSERT INTO trips (carId, startedAtMs, endedAtMs, distanceMm, fuelUl, fuelPricePerL, drivingMs, standingMs, source, externalId) " +
                    "VALUES (1, 1, 2, 3, 4, NULL, 1, 0, 'IMPORT_CARSCANNER', 'carscanner:7:1')",
            )
        }
        helper.runMigrationsAndValidate(dbName, 6, true, MIGRATION_5_6).use { db ->
            fun count(sql: String) = db.query(sql).use { c -> c.moveToFirst(); c.getLong(0) }
            assertEquals(1, count("SELECT COUNT(*) FROM readings"))
            assertEquals(1, count("SELECT COUNT(*) FROM trips"))
            assertEquals(0, count("SELECT COUNT(*) FROM import_history"))
            assertEquals(0, count("SELECT COUNT(*) FROM import_files"))
            assertEquals(0, count("SELECT COUNT(*) FROM import_snapshots"))
        }
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), EcuDatabase::class.java, dbName)
            .addMigrations(*EcuDatabase.MIGRATIONS).build()
        try {
            val dao = room.importDao()
            dao.putFile(ImportedFileEntity("a.brc", 10, 20, 30))
            dao.putFile(ImportedFileEntity("a.brc", 11, 21, 31))
            assertEquals(11L, dao.file("a.brc")!!.size)
            dao.putSnapshot(ImportSnapshotEntity("car:1", "{}"))
            assertEquals("{}", dao.snapshot("car:1"))
            dao.insertHistory(
                ImportHistoryEntity(
                    source = "CARSCANNER", fileName = "b.cbz", startedAtMs = 1, finishedAtMs = 2, status = "DONE", recordingsAdded = 1,
                    recordingsSkipped = 0, tripsAdded = 2, tripsUpdated = 0, tripsSkipped = 0, dtcAdded = 0, carFieldsUpdated = 0,
                    unitsUpdated = false, errors = 0, message = null,
                ),
            )
            assertEquals(listOf(2), dao.observeHistory().first().map { it.tripsAdded })
            val trip = room.tripDao().byExternalIds(listOf("carscanner:7:1")).single()
            room.tripDao().updateAll(listOf(trip.copy(endedAtMs = 5)))
            assertEquals(5L, room.tripDao().forCar(1).single().endedAtMs)
        } finally {
            room.close()
        }
    }

    @Test
    fun v6TemplateMaintenanceGetsKindsUserTextIsKept() = runTest {
        helper.createDatabase(dbName, 6).use { db ->
            db.execSQL("INSERT INTO cars (id, name) VALUES (1, 'Мой автомобиль')")
            db.execSQL(
                "INSERT INTO maintenance_items (id, carId, title, intervalKm, intervalMonths, lastDoneKm, lastDoneAtMs, note) " +
                    "VALUES (1, 1, 'Масло и масляный фильтр двигателя', 10000, 12, 50000, 5, 'В тяжёлых условиях (пробки, короткие поездки) — чаще')",
            )
            db.execSQL("INSERT INTO maintenance_items (id, carId, title, intervalKm, intervalMonths, lastDoneKm, lastDoneAtMs, note) VALUES (2, 1, 'Тормозная жидкость', NULL, 24, NULL, NULL, NULL)")
            db.execSQL("INSERT INTO maintenance_items (id, carId, title, intervalKm, intervalMonths, lastDoneKm, lastDoneAtMs, note) VALUES (3, 1, 'Мойка', NULL, 1, NULL, NULL, 'своя заметка')")
        }
        helper.runMigrationsAndValidate(dbName, 7, true, MIGRATION_6_7).use { db ->
            db.query("SELECT id, title, kind, note, noteKind, intervalKm, lastDoneKm FROM maintenance_items ORDER BY id").use { c ->
                c.moveToNext()
                assertEquals("", c.getString(1))
                assertEquals("OIL_AND_FILTER", c.getString(2))
                assertTrue(c.isNull(3))
                assertEquals("OIL_SEVERE_CONDITIONS", c.getString(4))
                assertEquals(10000, c.getInt(5))
                assertEquals(50000L, c.getLong(6))
                c.moveToNext()
                assertEquals("BRAKE_FLUID", c.getString(2))
                c.moveToNext()
                assertEquals("Мойка", c.getString(1))
                assertTrue(c.isNull(2))
                assertEquals("своя заметка", c.getString(3))
                assertTrue(c.isNull(4))
            }
            // the default car keeps its stored name: the app treats the legacy literal as the default name
            db.query("SELECT name FROM cars WHERE id = 1").use { c -> c.moveToFirst(); assertEquals("Мой автомобиль", c.getString(0)) }
        }
    }

    @Test
    fun importedRecordingOverlappingCarScannerTripGetsNoSecondTrip() = runTest {
        val room = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EcuDatabase::class.java).build()
        try {
            val car = room.carDao().insert(CarEntity(name = "Тестовая"))
            val dao = room.sessionDao()
            val covered = dao.insertSession(SessionEntity(name = "a", startedAtMs = 1_000, endedAtMs = 5_000, source = "IMPORT_CARSCANNER", vin = null, externalId = "carscanner:a", carId = car))
            val uncovered = dao.insertSession(SessionEntity(name = "b", startedAtMs = 10_000, endedAtMs = 20_000, source = "IMPORT_CARSCANNER", vin = null, externalId = "carscanner:b", carId = car))
            val live = dao.insertSession(SessionEntity(name = "c", startedAtMs = 2_000, endedAtMs = 3_000, source = "LIVE", vin = null, carId = car))
            dao.insertSession(SessionEntity(name = "running", startedAtMs = 30_000, endedAtMs = null, source = "LIVE", vin = null, carId = car))
            room.tripDao().insertAll(listOf(TripEntity(carId = car, startedAtMs = 900, endedAtMs = 4_000, distanceMm = 1, fuelUl = 1, fuelPricePerL = null, drivingMs = 1, standingMs = 0, source = "IMPORT_CARSCANNER", externalId = "carscanner:x")))
            assertEquals(listOf(live, uncovered), room.tripDao().sessionsWithoutTrip().map { it.id })
            room.tripDao().insertAll(listOf(TripEntity(carId = car, startedAtMs = 2_000, endedAtMs = 3_000, distanceMm = 1, fuelUl = 1, fuelPricePerL = null, drivingMs = 1, standingMs = 0, source = "LIVE", externalId = "session:$live", fuelMethod = "MAF_ESTIMATE")))
            assertEquals(listOf(uncovered), room.tripDao().sessionsWithoutTrip().map { it.id })
            assertTrue(covered > 0)
        } finally {
            room.close()
        }
    }

    @Test
    fun freshDatabaseHasDefaultCar() = runTest {
        val room = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EcuDatabase::class.java)
            .addCallback(EcuDatabase.defaultCar).build()
        try {
            assertEquals(listOf(EcuDatabase.DEFAULT_CAR_ID), room.carDao().all().map { it.id })
        } finally {
            room.close()
        }
    }

    @Test
    fun tripsAreDedupedByExternalIdAndCascadeWithCar() = runTest {
        val room = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EcuDatabase::class.java).build()
        try {
            val car = room.carDao().insert(CarEntity(name = "t"))
            fun trip(ext: String) = TripEntity(carId = car, startedAtMs = 1, endedAtMs = 2, distanceMm = 3, fuelUl = 4,
                fuelPricePerL = null, drivingMs = 1, standingMs = 0, source = "IMPORT_CARSCANNER", externalId = ext)
            assertEquals(2, room.tripDao().insertAll(listOf(trip("a"), trip("b"))).count { it > 0 })
            assertEquals(0, room.tripDao().insertAll(listOf(trip("a"))).count { it > 0 })
            assertEquals(2, room.tripDao().observeForCar(car).first().size)
            room.carDao().delete(car)
            assertEquals(0, room.tripDao().observeForCar(car).first().size)
        } finally {
            room.close()
        }
    }

    @Test
    fun v8GetsEventsAndHealthChecksKeepingDataAndMovesSessions() = runTest {
        helper.createDatabase(dbName, 8).use { db ->
            db.execSQL("INSERT INTO cars (id, name) VALUES (1, 'Тестовая')")
            db.execSQL("INSERT INTO cars (id, name) VALUES (2, 'Test car 2')")
            db.execSQL("INSERT INTO sessions (id, name, startedAtMs, endedAtMs, source, vin, externalId, carId) VALUES (1, 'live', 30, 40, 'LIVE', NULL, NULL, 1)")
            db.execSQL("INSERT INTO readings (sessionId, sensorId, value, unit, timestampMs) VALUES (1, '01.0C', 800.0, 'rpm', 31)")
            db.execSQL(
                "INSERT INTO trips (carId, startedAtMs, endedAtMs, distanceMm, fuelUl, fuelPricePerL, drivingMs, standingMs, source, externalId) " +
                    "VALUES (1, 30, 40, 1000, 10, NULL, 10, 0, 'LIVE', 'session:1')",
            )
            db.execSQL("INSERT INTO readiness_snapshots (carId, takenAtMs, reason) VALUES (1, 50, 'BEFORE_CLEAR')")
        }
        helper.runMigrationsAndValidate(dbName, 9, true, MIGRATION_8_9).use { db ->
            fun count(sql: String) = db.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(1, count("SELECT COUNT(*) FROM readings"))
            assertEquals(1, count("SELECT COUNT(*) FROM trips"))
            assertEquals(1, count("SELECT COUNT(*) FROM readiness_snapshots"))
            assertEquals(0, count("SELECT COUNT(*) FROM trip_events"))
            assertEquals(0, count("SELECT COUNT(*) FROM health_checks"))
        }
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), EcuDatabase::class.java, dbName)
            .addMigrations(*EcuDatabase.MIGRATIONS).build()
        try {
            room.eventDao().insert(TripEventEntity(sessionId = 1, carId = 1, atMs = 35, type = "STALL"))
            room.healthDao().insert(HealthCheckEntity(carId = 1, takenAtMs = 60, verdict = "OK", items = "", dtcs = null, readiness = null, counters = null, odometerKm = null))
            assertEquals(1, room.healthDao().observeForCar(1).first().size)
            room.sessionMoveDao().move(1, 2)
            assertEquals(2L, room.sessionMoveDao().carOf(1))
            assertEquals(1, room.tripDao().forCar(2).size)
            assertEquals(2L, room.eventDao().forSession(1).single().carId)
            room.sessionDao().delete(1)
            assertTrue("events go with their recording", room.eventDao().forSession(1).isEmpty())
        } finally {
            room.close()
        }
    }

    @Test
    fun v9GetsFuelLogAndExpensesKeepingData() = runTest {
        helper.createDatabase(dbName, 9).use { db ->
            db.execSQL("INSERT INTO cars (id, name, tankVolumeL) VALUES (1, 'Test car', 60.0)")
            db.execSQL("INSERT INTO sessions (id, name, startedAtMs, endedAtMs, source, vin, externalId, carId) VALUES (1, 'live', 30, 40, 'LIVE', NULL, NULL, 1)")
            db.execSQL("INSERT INTO readings (sessionId, sensorId, value, unit, timestampMs) VALUES (1, '01.0C', 800.0, 'rpm', 31)")
            db.execSQL(
                "INSERT INTO trips (carId, startedAtMs, endedAtMs, distanceMm, fuelUl, fuelPricePerL, drivingMs, standingMs, source, externalId) " +
                    "VALUES (1, 30, 40, 1000, 10, NULL, 10, 0, 'LIVE', 'session:1')",
            )
            db.execSQL("INSERT INTO maintenance_items (id, carId, title, intervalKm, intervalMonths) VALUES (1, 1, '', 10000, 12)")
            db.execSQL("INSERT INTO odometer_log (carId, km, atMs, source) VALUES (1, 1000, 20, 'MANUAL')")
            db.execSQL("INSERT INTO trip_events (sessionId, carId, atMs, type) VALUES (1, 1, 35, 'STALL')")
        }
        helper.runMigrationsAndValidate(dbName, 10, true, MIGRATION_9_10).use { db ->
            fun count(sql: String) = db.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(1, count("SELECT COUNT(*) FROM sessions"))
            assertEquals(1, count("SELECT COUNT(*) FROM readings"))
            assertEquals(1, count("SELECT COUNT(*) FROM trips"))
            assertEquals(1, count("SELECT COUNT(*) FROM maintenance_items"))
            assertEquals(1, count("SELECT COUNT(*) FROM odometer_log"))
            assertEquals(1, count("SELECT COUNT(*) FROM trip_events"))
            assertEquals(0, count("SELECT COUNT(*) FROM fuel_fillups"))
            assertEquals(0, count("SELECT COUNT(*) FROM expenses"))
            assertEquals(60, count("SELECT tankVolumeL FROM cars WHERE id = 1"))
        }
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), EcuDatabase::class.java, dbName)
            .addMigrations(*EcuDatabase.MIGRATIONS).build()
        try {
            val id = room.fuelDao().insert(
                FuelFillUpEntity(carId = 1, atMs = 50, odometerKm = 1500, litres = 40.0, pricePerL = 50.0, total = 2000.0, fullTank = true,
                    missedPrevious = false, station = null, note = null),
            )
            fun csv(ext: String) = FuelFillUpEntity(carId = 1, atMs = 60, odometerKm = null, litres = 1.0, pricePerL = null, total = null,
                fullTank = false, missedPrevious = false, station = null, note = null, externalId = ext)
            assertEquals(1, room.fuelDao().insertAll(listOf(csv("csv:a"))).count { it > 0 })
            assertEquals(0, room.fuelDao().insertAll(listOf(csv("csv:a"))).count { it > 0 })
            room.expenseDao().insert(ExpenseEntity(carId = 1, atMs = 70, category = "SERVICE", amount = 3000.0, odometerKm = 1600, note = null, maintenanceItemId = 1))
            assertEquals(1500L, room.fuelDao().byId(id)!!.odometerKm)
            assertEquals(1, room.expenseDao().forCar(1).size)
        } finally {
            room.close()
        }
    }
    @Test
    fun v10ReadingsGetTheCompositeIndexKeepingRows() = runTest {
        helper.createDatabase(dbName, 10).use { db ->
            db.execSQL("INSERT INTO cars (id, name) VALUES (1, 'Test car')")
            db.execSQL("INSERT INTO sessions (id, name, startedAtMs, endedAtMs, source, vin, externalId, carId) VALUES (1, 'live', 30, 40, 'LIVE', NULL, NULL, 1)")
            for (i in 0 until 50) {
                db.execSQL("INSERT INTO readings (sessionId, sensorId, value, unit, timestampMs) VALUES (1, '01.0${i % 5}', $i, 'u', ${100 + i})")
            }
        }
        helper.runMigrationsAndValidate(dbName, 11, true, MIGRATION_10_11).use { db ->
            fun count(sql: String) = db.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(50, count("SELECT COUNT(*) FROM readings"))
            assertEquals(1, count("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_readings_sessionId_sensorId_timestampMs'"))
            assertEquals(0, count("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_readings_sessionId'"))
            // The cascade still works through the composite index (sessionId is its first column).
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM sessions WHERE id = 1")
            assertEquals(0, count("SELECT COUNT(*) FROM readings"))
        }
    }

    @Test
    fun v11GetsTheCarNormsTableKeepingDataAndCascadesWithTheCar() = runTest {
        helper.createDatabase(dbName, 11).use { db ->
            db.execSQL("INSERT INTO cars (id, name) VALUES (1, 'Test car')")
            db.execSQL("INSERT INTO cars (id, name) VALUES (2, 'Second test car')")
            db.execSQL("INSERT INTO sessions (id, name, startedAtMs, endedAtMs, source, vin, externalId, carId) VALUES (1, 'live', 30, 40, 'LIVE', NULL, NULL, 1)")
            for (i in 0 until 20) {
                db.execSQL("INSERT INTO readings (sessionId, sensorId, value, unit, timestampMs) VALUES (1, '01.0C', $i, 'rpm', ${100 + i})")
            }
            db.execSQL("INSERT INTO findings (carId, ruleId, severity, title, message, evidenceJson, periodFromMs, periodToMs, createdAtMs) VALUES (1, 'voltage-low', 'OK', 't', 'm', '{}', NULL, NULL, 5)")
        }
        helper.runMigrationsAndValidate(dbName, 12, true, MIGRATION_11_12).use { db ->
            fun count(sql: String) = db.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(2, count("SELECT COUNT(*) FROM cars"))
            assertEquals(20, count("SELECT COUNT(*) FROM readings"))
            assertEquals(1, count("SELECT COUNT(*) FROM findings"))
            assertEquals(0, count("SELECT COUNT(*) FROM car_norms"))
            db.execSQL("INSERT INTO car_norms (carId, key, source, low, high, center, mad, sampleCount, confidence, updatedAtMs) VALUES (1, 'IDLE_RPM', 'BASELINE', 600, 800, 700, 20, 12, 0.4, 9)")
            db.execSQL("INSERT INTO car_norms (carId, key, source, low, high, center, mad, sampleCount, confidence, updatedAtMs) VALUES (1, 'IDLE_RPM', 'USER', 650, 750, NULL, NULL, 0, 1, 9)")
            db.execSQL("INSERT INTO car_norms (carId, key, source, low, high, center, mad, sampleCount, confidence, updatedAtMs) VALUES (2, 'trait.fuel', 'ECU', NULL, NULL, 1, NULL, 0, 1, 9)")
            assertEquals(3, count("SELECT COUNT(*) FROM car_norms"))
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM cars WHERE id = 2")
            assertEquals(2, count("SELECT COUNT(*) FROM car_norms"))
        }
    }

    @Test
    fun v12GetsTheModuleScansTableKeepingDataAndCascadesWithTheCar() = runTest {
        helper.createDatabase(dbName, 12).use { db ->
            db.execSQL("INSERT INTO cars (id, name) VALUES (1, 'Test car')")
            db.execSQL("INSERT INTO cars (id, name) VALUES (2, 'Second test car')")
            db.execSQL("INSERT INTO sessions (id, name, startedAtMs, endedAtMs, source, vin, externalId, carId) VALUES (1, 'live', 30, 40, 'LIVE', NULL, NULL, 1)")
            for (i in 0 until 10) {
                db.execSQL("INSERT INTO readings (sessionId, sensorId, value, unit, timestampMs) VALUES (1, '01.0C', $i, 'rpm', ${100 + i})")
            }
            db.execSQL("INSERT INTO dtc_snapshots (takenAtMs, vin, code, kind, carId) VALUES (35, NULL, 'P0113', 'STORED', 1)")
            db.execSQL("INSERT INTO car_norms (carId, key, source, low, high, center, mad, sampleCount, confidence, updatedAtMs) VALUES (1, 'IDLE_RPM', 'BASELINE', 600, 800, 700, 20, 12, 0.4, 9)")
        }
        helper.runMigrationsAndValidate(dbName, 13, true, MIGRATION_12_13).use { db ->
            fun count(sql: String) = db.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(10, count("SELECT COUNT(*) FROM readings"))
            assertEquals(1, count("SELECT COUNT(*) FROM dtc_snapshots"))
            assertEquals(1, count("SELECT COUNT(*) FROM car_norms"))
            assertEquals(0, count("SELECT COUNT(*) FROM module_scans"))
            db.execSQL("INSERT INTO module_scans (carId, scanAtMs, protocol, address, moduleKind, result, detail, dtcs) VALUES (1, 50, '6', '7D1/7D9', 'ABS_ESC', 'CODES', 'UDS_19_02', 'C0035:TC')")
            db.execSQL("INSERT INTO module_scans (carId, scanAtMs, protocol, address, moduleKind, result, detail, dtcs) VALUES (2, 50, '5', '0x10', 'ENGINE', 'NO_REPLY', NULL, '')")
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM cars WHERE id = 2")
            assertEquals(1, count("SELECT COUNT(*) FROM module_scans"))
        }
    }

    /** R2.2: a v7 database with realistic synthetic content goes through every migration to the current version. */
    @Test
    fun v7RealisticDataSurvivesTheWholeChain() = runTest {
        val sessions = 3
        val perSession = 1_200
        helper.createDatabase(dbName, 7).use { db ->
            db.execSQL("INSERT INTO cars (id, name, brand, model, year, engineVolumeL, tankVolumeL) VALUES (1, 'Тестовая', 'Kia', 'Sorento', 2005, 3.5, 80.0)")
            db.execSQL("INSERT INTO cars (id, name) VALUES (2, 'Test car')")
            for (s in 1..sessions) {
                val start = 1_000_000L * s
                val source = if (s == 1) "LIVE" else "IMPORT_CARSCANNER"
                val ext = if (s == 1) "NULL" else "'carscanner:$s.brc'"
                db.execSQL("INSERT INTO sessions (id, name, startedAtMs, endedAtMs, source, vin, externalId, carId) VALUES ($s, 's$s', $start, ${start + perSession * 100}, '$source', NULL, $ext, ${if (s == 3) 2 else 1})")
                db.beginTransaction()
                try {
                    for (i in 0 until perSession) {
                        val sensor = listOf("01.05", "01.0C", "01.0D", "01.06", "01.42", "elm.voltage")[i % 6]
                        db.execSQL("INSERT INTO readings (sessionId, sensorId, value, unit, timestampMs) VALUES ($s, '$sensor', ${i % 97}.5, 'u', ${start + i * 100})")
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                db.execSQL(
                    "INSERT INTO trips (carId, startedAtMs, endedAtMs, distanceMm, fuelUl, fuelPricePerL, drivingMs, standingMs, source, externalId, fuelMethod) " +
                        "VALUES (${if (s == 3) 2 else 1}, $start, ${start + 60_000}, 12000000, 900000, 50.0, 50000, 10000, '$source', 'trip:$s', NULL)",
                )
            }
            db.execSQL("INSERT INTO dtc_snapshots (takenAtMs, vin, code, kind, carId) VALUES (2000000, NULL, 'P0133', 'STORED', 1)")
            db.execSQL("INSERT INTO dtc_snapshots (takenAtMs, vin, code, kind, carId) VALUES (2000000, NULL, 'P0171', 'PENDING', 1)")
            db.execSQL("INSERT INTO maintenance_items (carId, title, intervalKm, intervalMonths, kind) VALUES (1, '', 10000, 12, 'OIL')")
            db.execSQL("INSERT INTO odometer_log (carId, km, atMs, source) VALUES (1, 150000, 1000, 'MANUAL')")
            db.execSQL(
                "INSERT INTO findings (carId, ruleId, severity, title, message, evidenceJson, periodFromMs, periodToMs, createdAtMs) " +
                    "VALUES (1, 'ltft', 'INFO', 't', 'm', '{}', NULL, NULL, 5)",
            )
        }
        val target = EcuDatabase.VERSION
        helper.runMigrationsAndValidate(dbName, target, true, *EcuDatabase.MIGRATIONS).use { db ->
            fun count(sql: String) = db.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(2, count("SELECT COUNT(*) FROM cars"))
            assertEquals(sessions, count("SELECT COUNT(*) FROM sessions"))
            assertEquals(sessions * perSession, count("SELECT COUNT(*) FROM readings"))
            assertEquals(perSession, count("SELECT COUNT(*) FROM readings WHERE sessionId = 2"))
            assertEquals(sessions, count("SELECT COUNT(*) FROM trips"))
            assertEquals(2, count("SELECT COUNT(*) FROM dtc_snapshots"))
            assertEquals(1, count("SELECT COUNT(*) FROM maintenance_items"))
            assertEquals(1, count("SELECT COUNT(*) FROM odometer_log"))
            assertEquals(1, count("SELECT COUNT(*) FROM findings"))
            assertEquals(0, count("SELECT COUNT(*) FROM readiness_snapshots"))
            assertEquals(0, count("SELECT COUNT(*) FROM health_checks"))
            assertEquals(0, count("SELECT COUNT(*) FROM fuel_fillups"))
            assertEquals(1, count("SELECT COUNT(*) FROM cars WHERE name = 'Тестовая' AND year = 2005"))
        }
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), EcuDatabase::class.java, dbName)
            .addMigrations(*EcuDatabase.MIGRATIONS).build()
        try {
            assertEquals(perSession, room.sessionDao().readings(1).size)
            assertEquals(perSession / 6, room.analyticsDao().sessionSample(1, "01.0C", 1).size)
        } finally {
            room.close()
        }
    }
}

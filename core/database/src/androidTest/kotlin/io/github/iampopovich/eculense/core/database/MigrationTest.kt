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

        // Room открывает мигрированную базу без ошибок схемы, DAO видят данные
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), EcuDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
        try {
            val f = FindingEntity(carId = 1, ruleId = "r", severity = "WARNING", title = "t", message = "m", evidenceJson = "{}", periodFromMs = null, periodToMs = null, createdAtMs = 1)
            room.findingDao().replaceForCar(1, listOf(f, f.copy(ruleId = "r2")))
            room.findingDao().replaceForCar(1, listOf(f.copy(ruleId = "r3")))
            assertEquals(listOf("r3"), room.findingDao().forCar(1).map { it.ruleId })
            assertEquals(2, room.sessionDao().readings(1).size)
            // находки удаляются вместе с машиной
            room.carDao().insert(CarEntity(name = "второй"))
            room.carDao().delete(1)
            assertEquals(0, room.findingDao().forCar(1).size)
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
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
            // без даты в профиле — дата миграции
            assertTrue(count("SELECT atMs FROM odometer_log WHERE carId = 2") >= before / 1000 * 1000)
            assertEquals(0, count("SELECT COUNT(*) FROM cars WHERE odometerKm IS NOT NULL OR odometerAtMs IS NOT NULL"))
        }
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), EcuDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
        try {
            assertEquals(listOf(120000L, 5000L), room.odometerDao().observeLatestManual().first().sortedBy { it.carId }.map { it.km })
            val id = room.maintenanceDao().insert(MaintenanceItemEntity(carId = 1, title = "Масло", intervalKm = 10000, intervalMonths = 12, lastDoneKm = null, lastDoneAtMs = null, note = null))
            room.maintenanceDao().update(room.maintenanceDao().byId(id)!!.copy(lastDoneKm = 119000, lastDoneAtMs = 900))
            assertEquals(119000L, room.maintenanceDao().forCar(1).single().lastDoneKm)
            // живая запись без своей поездки — кандидат на поездку
            assertEquals(listOf(1L), room.tripDao().sessionsWithoutTrip().map { it.id })
            // ТО и пробег удаляются вместе с машиной
            room.carDao().delete(1)
            assertEquals(0, room.maintenanceDao().forCar(1).size)
            assertEquals(0, room.odometerDao().forCar(1).size)
        } finally {
            room.close()
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
}

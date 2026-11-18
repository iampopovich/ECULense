package io.github.iampopovich.eculense.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            .addMigrations(MIGRATION_1_2).build()
        try {
            assertEquals(2, room.sessionDao().observeSessions().first().size)
            assertEquals(listOf(EcuDatabase.DEFAULT_CAR_NAME), room.carDao().all().map { it.name })
            assertEquals(1, room.sessionDao().readings(1).size)
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

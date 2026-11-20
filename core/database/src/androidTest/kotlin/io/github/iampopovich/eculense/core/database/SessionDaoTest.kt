package io.github.iampopovich.eculense.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EcuDatabase::class.java).build()
    private val dao = db.sessionDao()

    @After fun close() = db.close()

    @Test
    fun insertReadAndCascadeDelete() = runTest {
        val id = dao.insertSession(SessionEntity(name = "t", startedAtMs = 1, endedAtMs = null, source = "LIVE", vin = null))
        dao.insertReadings(listOf(ReadingEntity(sessionId = id, sensorId = "01.0C", value = 800.0, unit = "rpm", timestampMs = 2)))
        dao.finish(id, endedAtMs = 3)
        assertEquals(3L, dao.observeSessions().first().single().endedAtMs)
        assertEquals(1, dao.readings(id).size)
        dao.delete(id)
        assertEquals(0, dao.readings(id).size)
    }

    @Test
    fun deletingRecordingDeletesOnlyItsDerivedTrip() = runTest {
        val car = db.carDao().insert(CarEntity(name = "Test car"))
        val id = dao.insertSession(SessionEntity(name = "t", startedAtMs = 1_000, endedAtMs = 9_000, source = "IMPORT_CARSCANNER", vin = null, carId = car))
        val other = dao.insertSession(SessionEntity(name = "u", startedAtMs = 20_000, endedAtMs = 29_000, source = "LIVE", vin = null, carId = car))
        fun trip(ext: String, source: String, from: Long) =
            TripEntity(carId = car, startedAtMs = from, endedAtMs = from + 8_000, distanceMm = 1_000_000, fuelUl = 0, fuelPricePerL = null,
                drivingMs = 8_000, standingMs = 0, source = source, externalId = ext)
        db.tripDao().insertAll(
            listOf(trip("session:$id", "LIVE", 1_000), trip("carscanner:7", "IMPORT_CARSCANNER", 1_000), trip("session:$other", "LIVE", 20_000)),
        )
        dao.deleteWithDerivedTrips(id)
        assertEquals(listOf("carscanner:7", "session:$other"), db.tripDao().forCar(car).mapNotNull { it.externalId }.sorted())
        assertEquals(listOf(other), dao.observeSessions().first().map { it.id })
    }
}

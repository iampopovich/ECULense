package io.github.iampopovich.eculense.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalyticsDaoTest {
    private lateinit var db: EcuDatabase
    private lateinit var dao: AnalyticsDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EcuDatabase::class.java)
            .addCallback(EcuDatabase.defaultCar).build()
        dao = db.analyticsDao()
    }

    @After fun tearDown() = db.close()

    private suspend fun session(start: Long, carId: Long = EcuDatabase.DEFAULT_CAR_ID, readings: List<Triple<String, Double, Long>>): Long {
        val id = db.sessionDao().insertSession(SessionEntity(name = "s$start", startedAtMs = start, endedAtMs = start + 1000, source = "LIVE", vin = null, carId = carId))
        db.sessionDao().insertReadings(readings.map { (s, v, t) -> ReadingEntity(sessionId = id, sensorId = s, value = v, unit = "u", timestampMs = t) })
        return id
    }

    @Test
    fun aggregatesPerSessionAndAcrossSessions() = runTest {
        val a = session(100, readings = listOf(Triple("01.05", 70.0, 1), Triple("01.05", 90.0, 2), Triple("01.0C", 800.0, 1)))
        session(200, readings = listOf(Triple("01.05", 100.0, 1)))
        val other = db.carDao().insert(CarEntity(name = "other"))
        session(300, carId = other, readings = listOf(Triple("01.05", 120.0, 1)))

        val perSession = dao.sessionAggregates(a)
        assertEquals(listOf("01.05", "01.0C"), perSession.map { it.sensorId })
        assertEquals(80.0, perSession.first().mean, 1e-9)

        val all = dao.sensorAggregate(EcuDatabase.DEFAULT_CAR_ID, "01.05", 0, Long.MAX_VALUE)!!
        assertEquals(3, all.count)
        assertEquals(2, all.sessions)
        assertEquals(100.0, all.max, 1e-9) // чужая машина не попала
        assertNull(dao.sensorAggregate(EcuDatabase.DEFAULT_CAR_ID, "01.05", 150, 160))
        assertEquals(listOf(70.0, 90.0, 100.0), dao.sensorSample(EcuDatabase.DEFAULT_CAR_ID, "01.05", 0, Long.MAX_VALUE, 1).sorted())
    }

    @Test
    fun warmOnlyStartsAtFirstCoolantAbove80() = runTest {
        session(
            100,
            readings = listOf(
                Triple("01.05", 40.0, 1), Triple("01.07", -8.6, 2),
                Triple("01.05", 85.0, 10), Triple("01.07", 12.5, 11), Triple("01.07", 12.5, 12),
            ),
        )
        val warm = dao.warmSensorAggregate(EcuDatabase.DEFAULT_CAR_ID, "01.07", 0, Long.MAX_VALUE)!!
        assertEquals(2, warm.count)
        assertEquals(12.5, warm.min, 1e-9)
        assertEquals(listOf(12.5, 12.5), dao.warmSensorSample(EcuDatabase.DEFAULT_CAR_ID, "01.07", 0, Long.MAX_VALUE, 1))
    }

    @Test
    fun sessionsListWithCountsAndPointsByTime() = runTest {
        val a = session(100, readings = listOf(Triple("01.0C", 800.0, 5), Triple("01.05", 90.0, 1), Triple("01.11", 3.0, 2)))
        session(200, readings = emptyList())
        val rows = dao.sessions(EcuDatabase.DEFAULT_CAR_ID, 0, Long.MAX_VALUE, 10)
        assertEquals(listOf(200L, 100L), rows.map { it.startedAtMs })
        assertEquals(listOf(0, 3), rows.map { it.readings })
        assertEquals(listOf("01.05", "01.0C"), dao.points(a, listOf("01.05", "01.0C")).map { it.sensorId })
        assertEquals(1, dao.sessions(EcuDatabase.DEFAULT_CAR_ID, 0, Long.MAX_VALUE, 1).size)
    }
}

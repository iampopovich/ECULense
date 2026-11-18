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
}

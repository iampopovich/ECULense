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

/** R2.3/R3.3: session chart and CSV export read the recording page by page instead of loading it as a whole. */
@RunWith(AndroidJUnit4::class)
class SessionPagingDaoTest {
    private lateinit var db: EcuDatabase
    private lateinit var dao: SessionDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EcuDatabase::class.java)
            .addCallback(EcuDatabase.defaultCar).build()
        dao = db.sessionDao()
    }

    @After fun tearDown() = db.close()

    /** Two sensors; every timestamp appears twice per sensor (keyset ties must not drop or repeat rows). */
    private suspend fun session(): Long {
        val id = dao.insertSession(SessionEntity(name = "t", startedAtMs = 0, endedAtMs = 10_000, source = "LIVE", vin = null, carId = EcuDatabase.DEFAULT_CAR_ID))
        dao.insertReadings(
            (0 until 5_000).flatMap { i ->
                listOf(
                    ReadingEntity(sessionId = id, sensorId = "01.0D", value = i.toDouble(), unit = "km/h", timestampMs = 1_000L + i / 2),
                    ReadingEntity(sessionId = id, sensorId = "01.0C", value = 800.0, unit = "rpm", timestampMs = 1_000L + i / 2),
                )
            },
        )
        return id
    }

    @Test
    fun sensorListRangeAndPagesCoverEveryRowOnce() = runTest {
        val id = session()
        assertEquals(listOf("01.0C", "01.0D"), dao.sensorIds(id))
        assertEquals(1_000L, dao.firstTimestamp(id))
        val r = dao.sensorRange(id, "01.0D")!!
        assertEquals(1_000L, r.fromMs); assertEquals(3_499L, r.toMs); assertEquals("km/h", r.unit); assertEquals(5_000, r.count)
        assertNull(dao.sensorRange(id, "01.05"))

        val seen = mutableListOf<Double>()
        var afterTs = Long.MIN_VALUE
        var afterId = -1L
        while (true) {
            val page = dao.sensorPage(id, "01.0D", afterTs, afterId, 2_000L, 333)
            seen += page.map { it.value }
            if (page.size < 333) break
            afterTs = page.last().timestampMs; afterId = page.last().id
        }
        assertEquals((0 until 2_002).map { it.toDouble() }, seen)

        var all = 0
        afterTs = Long.MIN_VALUE; afterId = -1L
        var lastTs = Long.MIN_VALUE
        while (true) {
            val page = dao.readingsPage(id, afterTs, afterId, 777)
            page.forEach { check(it.timestampMs >= lastTs); lastTs = it.timestampMs }
            all += page.size
            if (page.size < 777) break
            afterTs = page.last().timestampMs; afterId = page.last().id
        }
        assertEquals(10_000, all)
    }
}

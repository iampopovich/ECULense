package io.github.iampopovich.eculense.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.ceil

/** R1.3 sampled median/p95 by position within the sensor; R2.1 hot readings queries run on the composite index. */
@RunWith(AndroidJUnit4::class)
class ReadingsSamplingAndIndexTest {
    private lateinit var db: EcuDatabase
    private lateinit var dao: AnalyticsDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EcuDatabase::class.java)
            .addCallback(EcuDatabase.defaultCar).build()
        dao = db.analyticsDao()
    }

    @After fun tearDown() = db.close()

    private val sensors = listOf("01.04", "01.05", "01.06", "01.0C", "01.0D", "01.42")

    /** Deterministic pseudo-random value of sensor [k] at row [i] around 1000·(k+1). */
    private fun value(k: Int, i: Int) = 1000.0 * (k + 1) + ((i * 7919L) % 997)

    /** 6 sensors polled round-robin: row i belongs to sensor i % 6 (the aliasing case of the old `id % 6`). */
    private suspend fun roundRobinSession(start: Long, rows: Int): Long {
        val id = db.sessionDao().insertSession(
            SessionEntity(name = "s$start", startedAtMs = start, endedAtMs = start + rows, source = "LIVE", vin = null, carId = EcuDatabase.DEFAULT_CAR_ID),
        )
        db.sessionDao().insertReadings(
            (0 until rows).map { i ->
                val k = i % sensors.size
                ReadingEntity(sessionId = id, sensorId = sensors[k], value = value(k, i), unit = "u", timestampMs = start + i)
            },
        )
        return id
    }

    private fun nearestRank(values: List<Double>, q: Double): Double {
        val sorted = values.sorted()
        return sorted[(ceil(q * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
    }

    @Test
    fun everySensorGetsItsShareAndTheMedianIsClose() = runTest {
        val id = roundRobinSession(1_000, 12_000)
        sensors.forEachIndexed { k, sensor ->
            val exact = (0 until 12_000).filter { it % 6 == k }.map { value(k, it) }
            assertEquals(2_000, exact.size)
            // Step 6 within the sensor: ~2000 / 6 values for EVERY sensor (the old global id % 6 left five empty).
            val sample = dao.sessionSample(id, sensor, 6)
            assertEquals(sensor, 334, sample.size)
            val median = nearestRank(sample, 0.5)
            val exactMedian = nearestRank(exact, 0.5)
            assertTrue("$sensor median $median vs $exactMedian", abs(median - exactMedian) / exactMedian < 0.01)
            assertTrue(sensor, abs(nearestRank(sample, 0.95) - nearestRank(exact, 0.95)) / nearestRank(exact, 0.95) < 0.01)
            // Step 1 (what 2000 values per sensor get from Percentiles.step) keeps all 2000.
            assertEquals(2_000, dao.sessionSample(id, sensor, 1).size)
        }
    }

    @Test
    fun pagingCrossesPageBoundariesWithoutLossOrDuplicates() = runTest {
        // 6 × 4096 + 7 rows: one sensor has more values than a page; equal timestamps are paged by id.
        val id = db.sessionDao().insertSession(SessionEntity(name = "p", startedAtMs = 1, endedAtMs = 2, source = "LIVE", vin = null, carId = EcuDatabase.DEFAULT_CAR_ID))
        val n = SAMPLE_PAGE * 2 + 7
        db.sessionDao().insertReadings((0 until n).map { i -> ReadingEntity(sessionId = id, sensorId = "01.0C", value = i.toDouble(), unit = "rpm", timestampMs = (i / 3).toLong()) })
        val all = mutableListOf<Double>()
        dao.forEachValue(id, "01.0C") { all += it }
        assertEquals((0 until n).map { it.toDouble() }, all)
    }

    @Test
    fun carWideAndWarmSamplesFollowSessionsAndWarmStart() = runTest {
        roundRobinSession(1_000, 600)
        val second = db.sessionDao().insertSession(SessionEntity(name = "w", startedAtMs = 50_000, endedAtMs = 60_000, source = "LIVE", vin = null, carId = EcuDatabase.DEFAULT_CAR_ID))
        db.sessionDao().insertReadings(
            listOf(
                ReadingEntity(sessionId = second, sensorId = "01.05", value = 60.0, unit = "C", timestampMs = 50_001),
                ReadingEntity(sessionId = second, sensorId = "01.06", value = 5.0, unit = "%", timestampMs = 50_002),
                ReadingEntity(sessionId = second, sensorId = "01.05", value = 85.0, unit = "C", timestampMs = 50_010),
                ReadingEntity(sessionId = second, sensorId = "01.06", value = 2.0, unit = "%", timestampMs = 50_011),
            ),
        )
        assertEquals(100 + 2, dao.sensorSample(EcuDatabase.DEFAULT_CAR_ID, "01.06", 0, Long.MAX_VALUE, 1).size)
        // The round-robin session's coolant (~2000 °C synthetic) is "warm" from its first row; the second session from 50_010.
        assertEquals(100 + 1, dao.sensorSample(EcuDatabase.DEFAULT_CAR_ID, "01.06", 0, Long.MAX_VALUE, 1, warmOnly = true).size)
        assertEquals(listOf(2.0), dao.sensorSample(EcuDatabase.DEFAULT_CAR_ID, "01.06", 10_000, Long.MAX_VALUE, 1, warmOnly = true))
        assertEquals(50_010L, dao.warmStart(second))
    }

    /** EXPLAIN QUERY PLAN rows mentioning the readings table. */
    private fun plan(sql: String, vararg args: Any): List<String> =
        db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql", args).use { c ->
            val detail = c.getColumnIndexOrThrow("detail")
            buildList { while (c.moveToNext()) add(c.getString(detail)) }
        }

    private fun assertIndexed(name: String, sql: String, vararg args: Any) {
        val rows = plan(sql, *args)
        val onReadings = rows.filter { Regex("""^(SCAN|SEARCH) (TABLE )?(readings|r)\b""").containsMatchIn(it) }
        assertTrue("$name: no readings step in $rows", onReadings.isNotEmpty())
        onReadings.forEach { step ->
            assertTrue("$name: $step", step.startsWith("SEARCH"))
            assertTrue("$name: $step", Regex("""USING (COVERING )?INDEX index_readings_sessionId_sensorId_timestampMs""").containsMatchIn(step))
        }
    }

    @Test
    fun hotQueriesUseTheCompositeIndex() = runTest {
        // Many sessions, as in a real DB: with a single session the planner rightly prefers a plain scan.
        repeat(20) { roundRobinSession(1_000L + it * 10_000, 300) }
        db.openHelper.writableDatabase.execSQL("ANALYZE")
        val car = EcuDatabase.DEFAULT_CAR_ID
        assertIndexed("valuesPage", "SELECT id, value, timestampMs FROM readings WHERE sessionId = ? AND sensorId = ? AND timestampMs >= ? AND (timestampMs > ? OR id > ?) ORDER BY timestampMs, id LIMIT 4096", 1, "01.0C", 0, 0, -1)
        assertIndexed("warmStart", "SELECT MIN(timestampMs) FROM readings WHERE sessionId = ? AND sensorId = '01.05' AND value >= 80", 1)
        assertIndexed("sessionAggregates", "SELECT sensorId, MIN(unit), COUNT(*), MIN(value), MAX(value), AVG(value) FROM readings WHERE sessionId = ? GROUP BY sensorId ORDER BY sensorId", 1)
        assertIndexed("points", "SELECT sensorId, value, timestampMs FROM readings WHERE sessionId = ? AND sensorId IN ('01.05', '01.0C') ORDER BY timestampMs", 1)
        assertIndexed("readings", "SELECT * FROM readings WHERE sessionId = ? ORDER BY timestampMs", 1)
        assertIndexed("sessions count", "SELECT s.id, (SELECT COUNT(*) FROM readings r WHERE r.sessionId = s.id) FROM sessions s WHERE s.carId = ?", car)
        assertIndexed(
            "sensorAggregate",
            "SELECT r.sensorId, COUNT(*), AVG(r.value) FROM readings r JOIN sessions s ON s.id = r.sessionId WHERE s.carId = ? AND s.startedAtMs BETWEEN ? AND ? AND r.sensorId = ? GROUP BY r.sensorId",
            car, 0, Long.MAX_VALUE, "01.05",
        )
        assertIndexed(
            "warmSensorAggregate",
            "WITH warm AS (SELECT r.sessionId, MIN(r.timestampMs) AS t FROM sessions s JOIN readings r ON r.sessionId = s.id " +
                "WHERE s.carId = ? AND s.startedAtMs BETWEEN ? AND ? AND r.sensorId = '01.05' AND r.value >= 80 GROUP BY r.sessionId) " +
                "SELECT r.sensorId, COUNT(*) FROM readings r JOIN sessions s ON s.id = r.sessionId JOIN warm w ON w.sessionId = r.sessionId " +
                "WHERE s.carId = ? AND s.startedAtMs BETWEEN ? AND ? AND r.sensorId = ? AND r.timestampMs >= w.t GROUP BY r.sensorId",
            car, 0, Long.MAX_VALUE, car, 0, Long.MAX_VALUE, "01.06",
        )
        // The old single-column index is gone, the composite one exists.
        val indexes = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'readings'").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertEquals(listOf("index_readings_sessionId_sensorId_timestampMs"), indexes.filter { it.startsWith("index_") })
    }
}

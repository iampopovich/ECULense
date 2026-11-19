package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.model.SensorReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStatsTest {
    @Test
    fun `basic stats`() {
        val readings = (1..100).map { SensorReading("01.05", it.toDouble(), "°C", it.toLong()) }
        assertEquals(SensorStats("01.05", 100, 1.0, 100.0, 50.5, 95.0), SessionStats.compute(readings).single())
    }

    @Test
    fun `stats are per sensor and sorted by id`() {
        val readings = listOf(
            SensorReading("01.0C", 800.0, "rpm", 1),
            SensorReading("01.05", 90.0, "°C", 1),
            SensorReading("01.0C", 1200.0, "rpm", 2),
        )
        assertEquals(listOf("01.05", "01.0C"), SessionStats.compute(readings).map { it.sensorId })
        assertEquals(1000.0, SessionStats.compute(readings).last().mean, 1e-9)
    }

    @Test
    fun `percentile of a downsampled sample`() {
        assertEquals(95.0, Percentiles.of((1..100).map { it.toDouble() }, 0.95)!!, 1e-9)
        assertEquals(7.0, Percentiles.of(listOf(7.0), 0.95)!!, 1e-9)
        // few values: p95 of three is the maximum (nearest rank), not the middle, otherwise p95 < mean
        assertEquals(24.2, Percentiles.of(listOf(4.7, 4.7, 24.2), 0.95)!!, 1e-9)
        assertEquals(2.0, Percentiles.of(listOf(1.0, 2.0, 3.0, 4.0), 0.5)!!, 1e-9)
        assertNull(Percentiles.of(emptyList(), 0.5))
        // decimation step: no more than maxSample values
        assertEquals(1, Percentiles.step(count = 1_000, maxSample = 2_000))
        assertEquals(50, Percentiles.step(count = 100_000, maxSample = 2_000))
    }

    @Test
    fun `agg merges and keeps mean`() {
        val a = Agg.EMPTY.add(10.0).add(20.0)
        val b = Agg.EMPTY.add(30.0)
        val m = a + b
        assertEquals(3, m.count)
        assertEquals(20.0, m.mean!!, 1e-9)
        assertEquals(10.0, m.min, 1e-9)
        assertEquals(30.0, m.max, 1e-9)
        assertNull(Agg.EMPTY.mean)
    }

    @Test
    fun `histogram median and min`() {
        val h = Histogram.EMPTY.add(13.3).add(14.0).add(14.0).add(14.1).add(14.2)
        assertEquals(14.0, h.quantile(0.5)!!, 0.05)
        assertEquals(13.3, h.quantile(0.0)!!, 0.05)
        assertEquals(5, (h + Histogram.EMPTY).count)
    }
}

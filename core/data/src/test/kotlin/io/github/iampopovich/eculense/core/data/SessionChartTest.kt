package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.UnitsSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionChartTest {
    private fun repo(): FakeSessionRepository = FakeSessionRepository().apply {
        // 20k speed values with one short peak, interleaved with rpm
        for (i in 0 until 20_000) {
            appended += SensorReading("01.0D", if (i == 12_345) 160.0 else 50.0, "km/h", 1_000L + i * 100L)
            appended += SensorReading("01.0C", 800.0, "rpm", 1_000L + i * 100L + 1)
        }
    }

    @Test
    fun `series has at most maxPoints points, keeps the peak and converts to mph`() = runTest {
        val s = SessionChart.load(repo(), 1, "01.0D", UnitsSettings(metricDistance = false), maxPoints = 400)!!
        assertEquals("mph", s.unit)
        assertTrue(s.points.size <= 400)
        assertEquals(20_000L, s.sourceCount)
        assertEquals(160.0 / UnitsSettings.KM_PER_MILE, s.points.maxOf { it.value }, 1e-9)
        assertEquals(50.0 / UnitsSettings.KM_PER_MILE, s.points.minOf { it.value }, 1e-9)
    }

    @Test
    fun `event window limits the read range and fahrenheit converts coolant`() = runTest {
        val r = FakeSessionRepository().apply { (0 until 100).forEach { appended += SensorReading("01.05", 90.0, "°C", it * 1_000L) } }
        val s = SessionChart.load(r, 1, "01.05", UnitsSettings(celsius = false), window = 10_000L..19_000L)!!
        assertEquals("°F", s.unit)
        assertEquals(10L, s.sourceCount)
        assertEquals(194.0, s.points.first().value, 1e-9)
        assertNull(SessionChart.load(r, 1, "01.0C", UnitsSettings()))
    }
}

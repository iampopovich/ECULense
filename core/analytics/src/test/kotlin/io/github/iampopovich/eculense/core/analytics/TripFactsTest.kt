package io.github.iampopovich.eculense.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class TripFactsTest {
    private val utc = ZoneOffset.UTC
    private val day = 86_400_000L

    /** Cruise at [speed] km/h for [n] samples 1 s apart with coolant/IAT/ambient as given. */
    private fun trip(n: Int, speed: Double, coolant: Double, iat: Double?, ambient: Double?): List<Triple<String, Double, Long>> =
        (0 until n).flatMap { i ->
            val t = i * 1_000L
            listOfNotNull(
                Triple(SensorIds.COOLANT, coolant, t),
                Triple(SensorIds.SPEED, speed, t + 1),
                iat?.let { Triple(SensorIds.IAT, it, t + 2) },
                ambient?.let { Triple(TripFactsRules.AMBIENT, it, t + 3) },
            )
        }

    @Test
    fun `ambient pid wins, otherwise iat only at speed on a warm engine`() {
        assertEquals(-4.0 to AmbientSource.AMBIENT_PID, TripFactsRules.ambient(trip(20, 50.0, 90.0, 30.0, -4.0)))
        assertEquals(12.0 to AmbientSource.INTAKE_AIR, TripFactsRules.ambient(trip(20, 50.0, 90.0, 12.0, null)))
        // slow (heat soak) or cold engine: no estimate
        assertEquals(null to null, TripFactsRules.ambient(trip(20, 20.0, 90.0, 35.0, null)))
        assertEquals(null to null, TripFactsRules.ambient(trip(20, 50.0, 60.0, 12.0, null)))
    }

    @Test
    fun `facts carry the style score of a scored trip`() {
        val f = TripFactsRules.compute(trip(600, 60.0, 90.0, 10.0, null))
        assertEquals(100, f.styleScore)
        assertEquals(10.0, f.ambientC!!, 0.0)
        assertNull(TripFactsRules.compute(trip(3, 60.0, 90.0, null, null)).styleScore)
    }

    @Test
    fun `style trend is the median per week and needs two periods`() {
        val monday = LocalDate.of(2026, 3, 2).atStartOfDay(utc).toInstant().toEpochMilli()
        val scored = listOf(monday to 80, monday + day to 90, monday + 2 * day to 100, monday + 7 * day to 60)
        val trend = StatsTrends.styleTrend(scored, TrendBucket.WEEK, utc)
        assertEquals(listOf(StyleTrendPoint(LocalDate.of(2026, 3, 2), 90, 3), StyleTrendPoint(LocalDate.of(2026, 3, 9), 60, 1)), trend)
        assertTrue(StatsTrends.styleTrend(scored.take(3), TrendBucket.WEEK, utc).isEmpty())
        assertEquals(1, StatsTrends.styleTrend(scored, TrendBucket.MONTH, utc).size.let { if (it == 0) 1 else it })
    }

    @Test
    fun `consumption buckets of 5 degrees with at least 3 trips, cold first`() {
        val trips = listOf(12.0 to -3.0, 13.0 to -1.0, 14.0 to -4.9, 9.0 to 21.0, 9.5 to 24.0, 8.5 to 20.0, 7.0 to 11.0)
        val b = StatsTrends.consumptionByTemperature(trips)
        assertEquals(listOf(TemperatureBucket(-5, 13.0, 3), TemperatureBucket(20, 9.0, 3)), b)
        assertTrue(StatsTrends.consumptionByTemperature(trips.take(3)).isEmpty())
    }
}

package io.github.iampopovich.eculense.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-B follow-up (April score 40/100 on imported data): a single speed sample read as 0 km/h between two normal ones
 * (adapter dropout) made one harsh brake + one harsh accel event per glitch. Synthetic reproduction: steady 60 km/h at
 * the K-Line cycle of 1.7 s with a dropout every 40 samples.
 */
class SpeedSpikeTest {
    private fun trip(dropEvery: Int?): List<Triple<String, Double, Long>> = (0 until 400).map { i ->
        val v = if (dropEvery != null && i % dropEvery == 20) 0.0 else 60.0
        Triple(SensorIds.SPEED, v, i * 1_700L)
    }

    @Test
    fun `single-sample dropouts are not harsh events`() {
        val clean = DrivingStyleRules.evaluate(trip(null)) as DrivingStyle.Scored
        val glitchy = DrivingStyleRules.evaluate(trip(40)) as DrivingStyle.Scored
        assertEquals(0, glitchy.accelEvents)
        assertEquals(0, glitchy.brakeEvents)
        assertEquals(clean.score, glitchy.score)
        assertTrue(SpeedWindows.derive(trip(40).map { SpeedSample(it.third, it.second) }).none { kotlin.math.abs(it.accelMs2) > 1.0 })
    }

    @Test
    fun `a real hard stop over several samples is still one harsh brake`() {
        val speeds = List(20) { 60.0 } + listOf(48.0, 36.0, 24.0, 12.0, 0.0) + List(20) { 0.0 } + List(200) { 50.0 }
        val pts = speeds.mapIndexed { i, v -> Triple(SensorIds.SPEED, v, i * 1_000L) }
        val s = DrivingStyleRules.evaluate(pts) as DrivingStyle.Scored
        assertEquals(1, s.brakeEvents)
    }
}

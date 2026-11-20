package io.github.iampopovich.eculense.core.sensors

import io.github.iampopovich.eculense.core.data.AccelSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccelCaptureRulesTest {
    @Test
    fun `sensor timestamps convert from elapsedRealtimeNanos to the wall clock`() {
        val wallNow = 1_700_000_123_456L
        val elapsedNow = 987_654_321_000_000L // ~11 days of uptime
        // An event 250 ms before "now" (sensor FIFO latency).
        assertEquals(wallNow - 250, SensorClock.toWallMs(elapsedNow - 250_000_000L, wallNow, elapsedNow))
        assertEquals(wallNow, SensorClock.toWallMs(elapsedNow, wallNow, elapsedNow))
        // Sub-millisecond parts truncate toward the event, never into the future.
        assertEquals(wallNow - 1, SensorClock.toWallMs(elapsedNow - 1_500_000L, wallNow, elapsedNow))
    }

    @Test
    fun `support needs gravity and some accelerometer`() {
        assertEquals(AccelSupport.LINEAR_ACCELERATION, AccelCaptureRules.support(hasLinear = true, hasGravity = true, hasAccelerometer = true))
        assertEquals(AccelSupport.ACCELEROMETER_MINUS_GRAVITY, AccelCaptureRules.support(hasLinear = false, hasGravity = true, hasAccelerometer = true))
        assertEquals(AccelSupport.NO_GRAVITY, AccelCaptureRules.support(hasLinear = true, hasGravity = false, hasAccelerometer = true))
        assertEquals(AccelSupport.NO_ACCELEROMETER, AccelCaptureRules.support(hasLinear = false, hasGravity = false, hasAccelerometer = false))
    }

    @Test
    fun `capture only during a recording, with the setting on, never for an automatic recording on a simulated link`() {
        val ok = AccelSupport.LINEAR_ACCELERATION
        assertEquals(5L, AccelCaptureRules.target(5, auto = false, enabled = true, simulated = false, support = ok))
        assertEquals(5L, AccelCaptureRules.target(5, auto = true, enabled = true, simulated = false, support = ok))
        // Demo/replay: only a recording the user started by hand.
        assertEquals(5L, AccelCaptureRules.target(5, auto = false, enabled = true, simulated = true, support = ok))
        assertNull(AccelCaptureRules.target(5, auto = true, enabled = true, simulated = true, support = ok))
        assertNull(AccelCaptureRules.target(null, auto = false, enabled = true, simulated = false, support = ok))
        assertNull(AccelCaptureRules.target(5, auto = false, enabled = false, simulated = false, support = ok))
        assertNull(AccelCaptureRules.target(5, auto = false, enabled = true, simulated = false, support = AccelSupport.NO_GRAVITY))
    }

    @Test
    fun `50 Hz without the high sampling rate permission`() {
        assertEquals(50, 1_000_000 / AccelCaptureRules.LINEAR_PERIOD_US)
        // HIGH_SAMPLING_RATE_SENSORS is required only above 200 Hz (Android 12 behaviour changes).
        assert(1_000_000 / AccelCaptureRules.LINEAR_PERIOD_US < 200)
    }
}

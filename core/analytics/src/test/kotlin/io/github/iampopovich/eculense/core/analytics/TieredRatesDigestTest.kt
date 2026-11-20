package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.model.SensorReading
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C2: live polling reads coolant, trims and voltage only every ~10th cycle. On a K-Line car (~1.6 s per cycle)
 * that is one sample per ~16 s; the digest must still see a warm engine, trims, idle MAF and long low-voltage runs.
 */
class TieredRatesDigestTest {
    private fun tieredIdle(minutes: Int, coolant: Double, voltage: Double): List<SensorReading> {
        val out = mutableListOf<SensorReading>()
        val cycleMs = 1_600L
        val cycles = minutes * 60_000L / cycleMs
        for (c in 0 until cycles) {
            val t = 1_000_000L + c * cycleMs
            out += SensorReading(SensorIds.RPM, 780.0, "rpm", t)
            out += SensorReading(SensorIds.SPEED, 0.0, "km/h", t + 10)
            if (c % 3 == 0L) out += SensorReading(SensorIds.MAF, 3.4, "g/s", t + 20)
            if (c % 10 == 0L) {
                out += SensorReading(SensorIds.COOLANT, coolant, "°C", t + 30)
                out += SensorReading(SensorIds.STFT1, 1.5, "%", t + 40)
                out += SensorReading(SensorIds.LTFT1, 9.0, "%", t + 50)
                out += SensorReading(SensorIds.MODULE_VOLTAGE, voltage, "V", t + 60)
            }
        }
        return out.sortedBy { it.timestampMs }
    }

    @Test
    fun `slow-tier trims and coolant still give a warm-engine digest`() {
        val d = SessionDigester.digest(1, 1_000_000L, tieredIdle(minutes = 5, coolant = 88.0, voltage = 12.9))
        assertTrue("trims ${d.warmTrims}", d.warmTrims.getValue(SensorIds.LTFT1).count >= Thresholds.MIN_TRIM_SAMPLES)
        assertTrue("maf ${d.warmIdleMaf}", d.warmIdleMaf.count >= Thresholds.MAF_IDLE_MIN_SAMPLES)
        assertTrue("voltage ${d.runningVoltage}", d.runningVoltage.count >= 10)
        assertTrue("low ${d.lowVoltageMs}", d.lowVoltageMs >= Thresholds.VOLTAGE_LOW_MIN_MS)
    }

    @Test
    fun `slow-tier coolant still detects a long overheat`() {
        val d = SessionDigester.digest(1, 1_000_000L, tieredIdle(minutes = 3, coolant = 107.0, voltage = 14.1))
        assertTrue("overheat ${d.overheatWarnMs}", d.overheatWarnMs >= Thresholds.OVERHEAT_MIN_MS)
    }
}

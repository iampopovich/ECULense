package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.analytics.SensorIds.COOLANT
import io.github.iampopovich.eculense.core.analytics.SensorIds.ELM_VOLTAGE
import io.github.iampopovich.eculense.core.analytics.SensorIds.LTFT1
import io.github.iampopovich.eculense.core.analytics.SensorIds.MAF
import io.github.iampopovich.eculense.core.analytics.SensorIds.MIL_DISTANCE
import io.github.iampopovich.eculense.core.analytics.SensorIds.MODULE_VOLTAGE
import io.github.iampopovich.eculense.core.analytics.SensorIds.RPM
import io.github.iampopovich.eculense.core.analytics.SensorIds.SPEED
import io.github.iampopovich.eculense.core.model.SensorReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Синтетические сессии: каждый тест строит ряд значений и проверяет одну величину дайджеста. */
class SessionDigesterTest {
    private fun r(id: String, v: Double, tSec: Long) = SensorReading(id, v, "", tSec * 1000)
    private fun digest(readings: List<SensorReading>) = SessionDigester.digest(1, 0, readings.sortedBy { it.timestampMs })

    @Test
    fun `coolant at 112 for 40 s is a long critical run`() {
        val d = digest((0..4).map { r(COOLANT, 112.0, it * 10L) })
        assertEquals(40_000, d.overheatCritMs)
        assertEquals(40_000, d.overheatWarnMs)
        assertEquals(112.0, d.coolant.max, 1e-9)
    }

    @Test
    fun `dip below the limit breaks the run`() {
        val values = listOf(112.0, 112.0, 100.0, 112.0, 112.0)
        val d = digest(values.mapIndexed { i, v -> r(COOLANT, v, i * 10L) })
        assertEquals(10_000, d.overheatCritMs)
    }

    @Test
    fun `voltage counts only while the engine runs`() {
        val readings = buildList {
            // стартер: обороты 0, напряжение 9 В — не считается
            add(r(RPM, 0.0, 0)); add(r(ELM_VOLTAGE, 9.0, 1))
            // работа: 800 об/мин, 13.0 В 70 секунд подряд
            for (t in 10L..80L step 5) { add(r(RPM, 800.0, t)); add(r(ELM_VOLTAGE, 13.0, t)) }
            add(r(RPM, 800.0, 85)); add(r(ELM_VOLTAGE, 14.1, 85))
        }
        val d = digest(readings)
        assertEquals(ELM_VOLTAGE, d.voltageSensor)
        assertEquals(13.0, d.runningVoltage.min, 1e-9)
        assertEquals(70_000, d.lowVoltageMs)
        assertEquals(0, d.highVoltageMs)
    }

    @Test
    fun `module voltage 01_42 is preferred over adapter voltage`() {
        val readings = listOf(r(RPM, 800.0, 0), r(ELM_VOLTAGE, 13.0, 1), r(MODULE_VOLTAGE, 14.2, 2))
        val d = digest(readings)
        assertEquals(MODULE_VOLTAGE, d.voltageSensor)
        assertEquals(14.2, d.runningVoltage.min, 1e-9)
    }

    @Test
    fun `trims are taken only on a warm engine`() {
        val readings = listOf(
            r(COOLANT, 40.0, 0), r(LTFT1, -8.6, 1),
            r(COOLANT, 85.0, 100), r(LTFT1, 12.5, 101), r(LTFT1, 12.5, 102),
        )
        val d = digest(readings)
        assertEquals(2, d.warmTrims.getValue(LTFT1).count)
        assertEquals(12.5, d.warmTrims.getValue(LTFT1).mean!!, 1e-9)
    }

    @Test
    fun `idle maf needs warm engine, zero speed and idle rpm`() {
        val readings = listOf(
            r(COOLANT, 90.0, 0), r(SPEED, 0.0, 0), r(RPM, 750.0, 0), r(MAF, 3.5, 1),
            r(RPM, 2500.0, 2), r(MAF, 15.0, 3), // перегазовка на месте — не холостой ход
            r(SPEED, 40.0, 4), r(RPM, 750.0, 4), r(MAF, 5.0, 5), // едем
            r(SPEED, 0.0, 6), r(MAF, 4.5, 7),
        )
        val d = digest(readings)
        assertEquals(2, d.warmIdleMaf.count)
        assertEquals(4.0, d.warmIdleMaf.mean!!, 1e-9)
    }

    @Test
    fun `stale rpm does not count as running`() {
        val readings = listOf(r(RPM, 800.0, 0), r(ELM_VOLTAGE, 12.0, 60))
        assertEquals(0, digest(readings).runningVoltage.count)
    }

    @Test
    fun `warm-up time from a cold start to 80 C`() {
        val readings = listOf(r(COOLANT, 10.0, 0), r(COOLANT, 60.0, 300), r(COOLANT, 80.0, 600), r(COOLANT, 90.0, 700))
        val d = digest(readings)
        assertEquals(10.0, d.coldStartC!!, 1e-9)
        assertEquals(600_000L, d.warmupMs)
        assertNull(digest(listOf(r(COOLANT, 70.0, 0), r(COOLANT, 85.0, 60))).warmupMs)
    }

    @Test
    fun `mil distance is tracked`() {
        val d = digest(listOf(r(MIL_DISTANCE, 700.0, 0), r(MIL_DISTANCE, 753.0, 100)))
        assertEquals(753.0, d.milDistanceKm.max, 1e-9)
        assertEquals(700.0, d.milDistanceKm.min, 1e-9)
    }

    @Test
    fun `empty session has no data`() {
        val d = digest(emptyList())
        assertEquals(0, d.coolant.count)
        assertNull(d.voltageSensor)
    }
}

package io.github.iampopovich.eculense.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingStyleAndEngineMapTest {
    /** Speed samples every [stepMs] from a list of km/h values; rpm alongside. */
    private fun drive(stepMs: Long, speeds: List<Double>, rpm: (Double) -> Double = { 2000.0 }): List<Triple<String, Double, Long>> =
        speeds.flatMapIndexed { i, v -> listOf(Triple(SensorIds.SPEED, v, i * stepMs), Triple(SensorIds.RPM, rpm(v), i * stepMs + 1)) }

    private fun cruise(n: Int, v: Double = 60.0) = List(n) { v }

    @Test
    fun `calm cruise scores 100`() {
        val s = DrivingStyleRules.evaluate(drive(1000, cruise(400))) as DrivingStyle.Scored
        assertEquals(100, s.score)
        assertEquals(0, s.accelEvents)
        assertNull(s.topPenalty)
        assertTrue(s.distanceKm > 6.0)
    }

    @Test
    fun `harsh accelerations and brakings are counted once per event and cost points`() {
        // 0 → 60 km/h in 4 s (≈ 4.2 m/s²), cruise, then 60 → 0 in 3 s (≈ 5.6 m/s²), three times
        val one = listOf(0.0, 15.0, 30.0, 45.0, 60.0) + cruise(150) + listOf(40.0, 20.0, 0.0) + List(5) { 0.0 }
        val s = DrivingStyleRules.evaluate(drive(1000, one + one + one)) as DrivingStyle.Scored
        assertEquals(3, s.accelEvents)
        assertEquals(3, s.brakeEvents)
        assertEquals(StylePenalty.HARSH_BRAKE, s.topPenalty)
        assertTrue(s.score < 80)
    }

    @Test
    fun `the threshold is scaled down for 2 s samples`() {
        // +20 km/h per 2 s = 2.78 m/s²: under 3.0 at 1 s, over 3.0 × 0.85 = 2.55 at 2 s
        val speeds = listOf(20.0, 40.0, 60.0) + cruise(200)
        val s = DrivingStyleRules.evaluate(drive(2000, speeds)) as DrivingStyle.Scored
        assertEquals(1, s.accelEvents)
    }

    @Test
    fun `sparse speed samples and short trips are not scored and say why`() {
        val sparse = DrivingStyleRules.evaluate(drive(2500, cruise(200)))
        assertEquals(DrivingStyle.NotScored(StyleSkipReason.SPARSE_SAMPLES, 2500), sparse)
        assertEquals(StyleSkipReason.TOO_SHORT, (DrivingStyleRules.evaluate(drive(1000, cruise(20))) as DrivingStyle.NotScored).reason)
        assertEquals(DrivingStyle.NotScored(StyleSkipReason.NO_SPEED), DrivingStyleRules.evaluate(emptyList()))
        assertTrue(DrivingStyleRules.english(sparse).contains("2500 ms"))
    }

    @Test
    fun `high rpm share and long idling cost points`() {
        val speeds = cruise(300, 90.0) + List(300) { 0.0 }
        val s = DrivingStyleRules.evaluate(drive(1000, speeds) { v -> if (v > 50) 4000.0 else 800.0 }) as DrivingStyle.Scored
        assertEquals(0.5, s.highRpmShare!!, 0.01)
        assertTrue(s.penalties.getValue(StylePenalty.HIGH_RPM) == StyleThresholds.HIGH_RPM_CAP)
        assertTrue(s.penalties.getValue(StylePenalty.IDLE) > 0.0)
    }

    /** Warm samples: coolant 90, then STFT/LTFT/rpm/load per step. */
    private fun map(samples: List<Triple<Double, Double?, Double>>, bank: Int = 1): List<Triple<String, Double, Long>> {
        val stft = if (bank == 1) SensorIds.STFT1 else SensorIds.STFT2
        val ltft = if (bank == 1) SensorIds.LTFT1 else SensorIds.LTFT2
        return listOf(Triple(SensorIds.COOLANT, 90.0, 0L)) + samples.flatMapIndexed { i, (rpm, load, trim) ->
            val t = 1000L + i * 1000
            listOfNotNull(
                Triple(SensorIds.RPM, rpm, t), load?.let { Triple(EngineMaps.LOAD, it, t) }, Triple(ltft, trim - 2.0, t), Triple(stft, 2.0, t + 10),
            )
        }
    }

    @Test
    fun `trims high at idle and falling under load read as an air leak hypothesis`() {
        val idle = List(10) { Triple(750.0, 20.0, 14.0) }
        val load = List(10) { Triple(3000.0, 70.0, 3.0) }
        val m = EngineMaps.build(map(idle + load))
        assertEquals(20, m.points.size)
        assertEquals(EngineMapPattern.AIR_LEAK_LIKE, m.summary.pattern)
        assertEquals(MapAxis.LOAD, m.summary.loadAxis)
        assertEquals(14.0, m.summary.idleMedian!!, 1e-9)
        val idleBin = m.rpmBins.first { it.from == 0.0 }
        assertEquals(10, idleBin.count)
        assertEquals(14.0, idleBin.median!!, 1e-9)
        assertTrue(EngineMaps.english(m.summary.pattern).startsWith("Hypothesis"))
    }

    @Test
    fun `rising under load without a load pid uses rpm and reads as fuel delivery`() {
        val idle = List(8) { Triple(800.0, null, 1.0) }
        val high = List(8) { Triple(3200.0, null, 12.0) }
        val s = EngineMaps.build(map(idle + high)).summary
        assertEquals(MapAxis.RPM, s.loadAxis)
        assertEquals(EngineMapPattern.FUEL_DELIVERY_LIKE, s.pattern)
    }

    @Test
    fun `cold samples stale ltft and few points give no pattern`() {
        val cold = listOf(Triple(SensorIds.COOLANT, 30.0, 0L)) + map(List(10) { Triple(800.0, 20.0, 15.0) }).drop(1)
        assertEquals(0, EngineMaps.build(cold).points.size)
        val few = EngineMaps.build(map(List(3) { Triple(800.0, 20.0, 15.0) } + List(3) { Triple(3000.0, 70.0, 0.0) }))
        assertEquals(EngineMapPattern.NOT_ENOUGH_DATA, few.summary.pattern)
        assertNull(few.rpmBins.first().median)
        val normal = EngineMaps.build(map(List(10) { Triple(800.0, 20.0, 2.0) } + List(10) { Triple(3000.0, 70.0, -1.0) }, bank = 2))
        assertEquals(listOf(2), normal.banks)
        assertEquals(EngineMapPattern.NORMAL, normal.summary.pattern)
    }
}

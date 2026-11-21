package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.analytics.SensorIds.LTFT1
import io.github.iampopovich.eculense.core.analytics.SensorIds.STFT1
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.FuelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** R-H: per-car norms on synthetic cars (neutral data). */
class CarNormsTest {
    private val zone = ZoneOffset.UTC
    private val day = 86_400_000L
    private val now = 400 * day
    private fun hist(n: Int, v: Double) = (1..n).fold(Histogram.EMPTY) { h, _ -> h.add(v) }
    private fun agg(n: Int, v: Double) = (1..n).fold(Agg.EMPTY) { a, _ -> a.add(v) }
    private fun digest(id: Long) = SessionDigest(sessionId = id, startedAtMs = id * day)
    private fun msg(f: Finding) = FindingEnglish.message(f, zone)
    private fun evidence(f: Finding) = f.evidence.joinToString(" | ") { FindingEnglish.render(it.label, zone) + ": " + FindingEnglish.render(it.value, zone) }

    private fun input(sessions: List<SessionDigest>, norms: CarNorms, dtc: List<DtcEvent> = emptyList()) =
        AnalysisInput(norms.car, sessions, dtc, now, zone, norms = norms)

    /** One recompute as the repository does it: evaluate with stored norms, learn, evaluate again with the result. */
    private fun recompute(sessions: List<SessionDigest>, stored: CarNorms, dtc: List<DtcEvent> = emptyList()): Pair<List<Finding>, CarNorms> {
        val first = AnalyticsEngine.evaluate(input(sessions, stored, dtc))
        val learned = BaselineLearner.learn(sessions, first, dtc, stored, now)
        val next = stored.copy(stored = stored.stored.filter { it.source != NormSource.BASELINE } + learned)
        return AnalyticsEngine.evaluate(input(sessions, next, dtc)) to next
    }

    @Test
    fun `median and scaled MAD follow Leys et al`() {
        val v = listOf(1.0, 3.0, 3.0, 6.0, 8.0, 10.0, 10.0, 1000.0)
        assertEquals(7.0, BaselineLearner.median(v), 1e-9)
        assertEquals(5.1891, BaselineLearner.mad(v), 1e-4)
    }

    @Test
    fun `petrol start-stop car with a smart alternator cruising at 12_6 V is not undercharging once its baseline is learned`() {
        val car = CarProfile(name = "Test car", fuelType = FuelType.PETROL, engineVolumeL = 1.4)
        val sessions = (1L..12L).map { id ->
            digest(id).copy(
                runningVoltage = agg(40, 12.6), runningVoltageHist = hist(40, 12.6) + hist(10, 14.6),
                drivingVoltageHist = hist(40, 12.6 + (id % 3) * 0.05), idleVoltageHist = hist(20, 14.4),
            )
        }
        val generic = CarNorms(car = car)
        val before = VoltageRule.evaluate(input(sessions, generic)).map(AnalyticsEngine::withNorms).first { it.ruleId == "voltage-low" }
        // the generic 13.2 V fires, but flagged as a general norm and never above WARNING
        assertEquals(Severity.WARNING, before.severity)
        assertTrue(msg(before), msg(before).contains("general norm, not verified for your engine"))

        val (after, norms) = recompute(sessions, generic)
        val driving = norms.effective(NormKey.CHARGING_DRIVING_V)!!
        assertEquals(NormSource.BASELINE, driving.source)
        assertEquals(12, driving.sampleCount)
        val low = after.first { it.ruleId == "voltage-low" }
        assertEquals(Severity.OK, low.severity)
        assertTrue(evidence(low), evidence(low).contains("Charging voltage while driving: 12.") && evidence(low).contains("own baseline, recordings: 12"))
    }

    @Test
    fun `a healthy 14_3 V above a 13_9 V baseline is only INFO, a drift out of the regulator window is a WARNING`() {
        val norms = CarNorms(listOf(Norm(NormKey.CHARGING_IDLE_V, 13.53, 14.27, NormSource.BASELINE, center = 13.9, sampleCount = 30)))
        fun at(v: Double) = (1L..3L).map { digest(it).copy(runningVoltage = agg(20, v), runningVoltageHist = hist(20, v), idleVoltageHist = hist(20, v)) }
        val mild = AnalyticsEngine.evaluate(input(at(14.3), norms)).first { it.ruleId == "voltage-high" }
        assertEquals(Severity.INFO, mild.severity)
        assertEquals(FindingKey.VOLTAGE_DIFFERS_NORM, mild.message.first().key)
        assertEquals(Severity.WARNING, AnalyticsEngine.evaluate(input(at(14.95), norms)).first { it.ruleId == "voltage-high" }.severity)
    }

    @Test
    fun `a dead alternator is never learned as the norm`() {
        val sessions = (1L..8L).map { digest(it).copy(runningVoltage = agg(20, 11.8), runningVoltageHist = hist(20, 11.8), drivingVoltageHist = hist(20, 11.8)) }
        val (findings, norms) = recompute(sessions, CarNorms())
        assertNull(norms.baseline(NormKey.CHARGING_DRIVING_V))
        val low = findings.first { it.ruleId == "voltage-low" }
        assertEquals(Severity.WARNING, low.severity)
        assertTrue(low.message.any { it.key == FindingKey.VOLTAGE_NOT_CHARGING })
        assertTrue("class 2 is absolute, not flagged generic", low.norms.isEmpty())
    }

    @Test
    fun `turbo diesel with wideband O2 - petrol rules are disabled, not fired`() {
        val traits = EngineTraitDetector.fromSupportedPids(setOf(0x05, 0x0C, 0x0D, 0x10, 0x24, 0x70, 0x51)).copy(
            fuel = Trait(EngineTraitDetector.fuelFromPid51(4)!!, NormSource.ECU),
        )
        assertEquals(O2Kind.WIDEBAND, traits.o2?.value)
        assertEquals(true, traits.forcedInduction?.value)
        assertTrue(traits.isDiesel)
        val car = CarProfile(name = "Test car", engineVolumeL = 2.0)
        val norms = CarNorms(car = car, traits = traits)
        // diesel idles unthrottled at ~2× the petrol air flow and runs lean trims
        val sessions = (1L..3L).map { digest(it).copy(warmIdleMaf = agg(20, 5.5), warmTrims = mapOf(LTFT1 to agg(20, 14.0), STFT1 to agg(20, 13.0))) }
        val f = AnalyticsEngine.evaluate(input(sessions, norms))
        listOf("fuel-trim-long", "fuel-trim-total", "fuel-trim-bank-asymmetry").forEach { id ->
            assertEquals(id, Severity.NO_DATA, f.first { it.ruleId == id }.severity)
        }
        val maf = f.first { it.ruleId == "maf-idle" }
        assertEquals(Severity.NO_DATA, maf.severity)
        assertTrue(maf.message.single().key == FindingKey.MAF_LEARNING)
        // health check: no trim verdict on a diesel, no 0.1–0.9 V swing check on a wideband sensor
        val h = HealthCheck.evaluate(
            HealthInput(emptyList(), emptyList(), null, true, false, 5, 0, false,
                mapOf(SensorIds.RPM to List(5) { 780.0 }, SensorIds.SPEED to List(5) { 0.0 }, SensorIds.COOLANT to List(5) { 88.0 },
                    LTFT1 to List(5) { 14.0 }, STFT1 to List(5) { 13.0 }, HealthCheck.O2 to List(5) { 0.45 })),
            norms,
        )
        assertEquals(HealthNote.NOT_APPLICABLE_DIESEL, h.items.first { it.id == HealthItemId.FUEL_TRIMS }.note)
        assertEquals(HealthNote.WIDEBAND_O2, h.items.first { it.id == HealthItemId.O2_SENSOR }.note)
        assertNull(GenericNorms.of(NormKey.O2_SWING, car, traits))
    }

    @Test
    fun `diesel learns its own idle air flow and then judges it`() {
        val car = CarProfile(name = "Test car", fuelType = FuelType.DIESEL, engineVolumeL = 2.0)
        val traits = EngineTraits().withProfile(car)
        val normal = (1L..10L).map { digest(it).copy(warmIdleMaf = agg(20, 5.4 + (it % 2) * 0.2)) }
        val (_, norms) = recompute(normal, CarNorms(car = car, traits = traits))
        assertEquals(NormSource.BASELINE, norms.effective(NormKey.IDLE_MAF)?.source)
        val leak = normal + (11L..13L).map { digest(it).copy(warmIdleMaf = agg(20, 3.9)) }
        val f = AnalyticsEngine.evaluate(input(leak, norms)).first { it.ruleId == "maf-idle" }
        assertEquals(Severity.WARNING, f.severity)
        assertFalse(msg(f).contains("general norm"))
    }

    @Test
    fun `thermostat at 92 C - plateau baseline catches a thermostat stuck open that the generic band cannot`() {
        val warm = (1L..10L).map { digest(it).copy(coolant = agg(60, 92.0), coolantHist = hist(60, 92.0 + (it % 3) - 1)) }
        val (_, norms) = recompute(warm, CarNorms())
        val plateau = norms.effective(NormKey.COOLANT_PLATEAU)!!
        assertEquals(NormSource.BASELINE, plateau.source)
        assertEquals(92.0, plateau.center!!, 0.5)
        val stuckOpen = warm + (11L..13L).map { digest(it).copy(coolant = agg(60, 84.0), coolantHist = hist(60, 84.0)) }
        assertEquals(Severity.WARNING, CoolantPlateauRule.evaluate(input(stuckOpen, norms)).single().severity)
        // without the baseline: 84 °C is inside any generic band, no false or true alarm
        assertEquals(Severity.OK, CoolantPlateauRule.evaluate(input(stuckOpen, CarNorms())).single().severity)
    }

    @Test
    fun `developing fault - baseline stays frozen while the finding is active`() {
        val healthy = (1L..10L).map { digest(it).copy(warmTrims = mapOf(LTFT1 to agg(20, 2.0 + (it % 3) * 0.5))) }
        val (_, learned) = recompute(healthy, CarNorms())
        val base = learned.baseline(NormKey.LTFT_B1)!!
        val drifting = healthy + (11L..16L).map { digest(it).copy(warmTrims = mapOf(LTFT1 to agg(20, 12.0 + it))) }
        val (f1, n1) = recompute(drifting, learned)
        val lt = f1.first { it.ruleId == "fuel-trim-long" }
        assertEquals(Severity.WARNING, lt.severity)
        assertEquals(NormSource.BASELINE, lt.norms.single().source)
        assertEquals(base, n1.baseline(NormKey.LTFT_B1))
        // more faulty trips: still frozen, the fault never becomes the norm
        val (_, n2) = recompute(drifting + (17L..30L).map { digest(it).copy(warmTrims = mapOf(LTFT1 to agg(20, 25.0))) }, n1)
        assertEquals(base, n2.baseline(NormKey.LTFT_B1))
    }

    @Test
    fun `generic-only car - severity capped at warning and flagged`() {
        val d = digest(1).copy(warmTrims = mapOf(LTFT1 to agg(20, 20.0), STFT1 to agg(20, 10.0)))
        val total = AnalyticsEngine.evaluate(input(listOf(d), CarNorms())).first { it.ruleId == "fuel-trim-total" }
        assertEquals(Severity.WARNING, total.severity)
        assertTrue(total.message.any { it.key == FindingKey.GENERIC_NORM_NOTE })
        // the same trims against the user's own norm keep CRITICAL
        val user = CarNorms(listOf(Norm(NormKey.TOTAL_TRIM, -20.0, 20.0, NormSource.USER)))
        assertEquals(Severity.CRITICAL, AnalyticsEngine.evaluate(input(listOf(d), user)).first { it.ruleId == "fuel-trim-total" }.severity)
    }

    @Test
    fun `a baseline needs five sessions and stored codes of the system keep sessions out`() {
        val four = (1L..4L).map { digest(it).copy(warmIdleRpmHist = hist(20, 700.0)) }
        val (_, n4) = recompute(four, CarNorms())
        assertEquals(4, n4.baseline(NormKey.IDLE_RPM)!!.sampleCount)
        assertEquals(NormSource.GENERIC, n4.effective(NormKey.IDLE_RPM)!!.source)
        val more = four + (5L..8L).map { digest(it).copy(warmIdleRpmHist = hist(20, 700.0)) }
        val dtc = listOf(DtcEvent("P0507", DtcKind.STORED, 8 * day))
        val (_, n8) = recompute(more, CarNorms(), dtc)
        // sessions 1..8 — the code read on day 8 keeps days 1..15 out: none learned at all
        assertNull(n8.baseline(NormKey.IDLE_RPM))
        assertEquals(8, BaselineLearner.learn(more, emptyList(), emptyList(), CarNorms(), now).first { it.key == NormKey.IDLE_RPM }.sampleCount)
    }

    @Test
    fun `user norm wins over baseline and generic`() {
        val norms = CarNorms(listOf(Norm(NormKey.IDLE_RPM, 600.0, 800.0, NormSource.BASELINE, center = 700.0, sampleCount = 10), Norm(NormKey.IDLE_RPM, 650.0, 750.0, NormSource.USER)))
        assertEquals(NormSource.USER, norms.effective(NormKey.IDLE_RPM)!!.source)
        assertEquals(NormSource.BASELINE, norms.copy(stored = norms.stored.take(1)).effective(NormKey.IDLE_RPM)!!.source)
    }

    @Test
    fun `traits from the ECU - fuel type, boost and recorded O2 sensors`() {
        assertEquals(FuelKind.PETROL, EngineTraitDetector.fuelFromPid51(1))
        assertEquals(FuelKind.DIESEL, EngineTraitDetector.fuelFromPid51(4))
        assertNull(EngineTraitDetector.fuelFromPid51(0))
        assertEquals(true, EngineTraitDetector.forcedFromBoost(listOf(99.0, 140.0, 150.0, 160.0), 100.0))
        assertNull(EngineTraitDetector.forcedFromBoost(listOf(99.0, 101.0, 103.0), 100.0))
        assertEquals(O2Kind.NARROWBAND, EngineTraitDetector.fromRecordedSensors(setOf("01.0C", "01.14", "01.15")).o2?.value)
        assertEquals(O2Kind.WIDEBAND, EngineTraitDetector.fromRecordedSensors(setOf("01.34", "01.15")).o2?.value)
        assertNull(EngineTraitDetector.fromRecordedSensors(setOf("01.0C")).hasMaf)
    }

    @Test
    fun `compared-with evidence names value, source and sample count in English`() {
        val n = Norm(NormKey.LTFT_B1, -3.0, 5.0, NormSource.BASELINE, center = 1.0, sampleCount = 12)
        assertEquals("Long-term trim, bank 1: -3.0…+5.0 % (own baseline, recordings: 12)", NormText.english(n))
        assertNotNull(GenericNorms.of(NormKey.IDLE_MAF, CarProfile(name = "Test car", engineVolumeL = 2.0), EngineTraits()))
    }
}

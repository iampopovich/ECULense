package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.analytics.SensorIds.ELM_VOLTAGE
import io.github.iampopovich.eculense.core.analytics.SensorIds.LTFT1
import io.github.iampopovich.eculense.core.analytics.SensorIds.LTFT2
import io.github.iampopovich.eculense.core.analytics.SensorIds.STFT1
import io.github.iampopovich.eculense.core.analytics.SensorIds.STFT2
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.DtcKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class RulesTest {
    private val zone = ZoneOffset.UTC
    private val day = 86_400_000L
    private fun aggOf(vararg v: Double) = v.fold(Agg.EMPTY) { a, x -> a.add(x) }
    private fun digest(id: Long = 1, start: Long = id * day) = SessionDigest(sessionId = id, startedAtMs = start)
    private fun input(sessions: List<SessionDigest> = emptyList(), dtc: List<DtcEvent> = emptyList(), car: CarProfile? = null) =
        AnalysisInput(car, sessions, dtc, nowMs = 400 * day, zone = zone)
    private fun ms(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun AnalyticsRule.only(input: AnalysisInput): Finding = evaluate(input).single()

    // --- overheating ---
    @Test
    fun `overheat above 110 for 30 s is critical, 105 is warning, 106 peak is ok`() {
        val crit = CoolantOverheatRule.only(input(listOf(digest().copy(coolant = aggOf(112.0), overheatWarnMs = 40_000, overheatCritMs = 40_000))))
        assertEquals(Severity.CRITICAL, crit.severity)
        assertEquals(listOf(1L), crit.sessionIds)
        val warn = CoolantOverheatRule.only(input(listOf(digest().copy(coolant = aggOf(107.0), overheatWarnMs = 31_000))))
        assertEquals(Severity.WARNING, warn.severity)
        val ok = CoolantOverheatRule.only(input(listOf(digest().copy(coolant = aggOf(90.0, 106.0), overheatWarnMs = 5_000))))
        assertEquals(Severity.OK, ok.severity)
        assertTrue(ok.evidence.any { it.value.contains("106") })
    }

    @Test
    fun `no coolant sensor means no data, not a false finding`() {
        assertEquals(Severity.NO_DATA, CoolantOverheatRule.only(input(listOf(digest()))).severity)
        assertEquals(Severity.NO_DATA, CoolantOverheatRule.only(input()).severity)
    }

    // --- voltage ---
    @Test
    fun `low voltage for a minute while running`() {
        val low = digest().copy(voltageSensor = ELM_VOLTAGE, runningVoltage = aggOf(12.9, 13.0), lowVoltageMs = 61_000)
        val findings = VoltageRule.evaluate(input(listOf(low)))
        assertEquals(Severity.WARNING, findings.first { it.ruleId == "voltage-low" }.severity)
        assertEquals(Severity.OK, findings.first { it.ruleId == "voltage-high" }.severity)
    }

    @Test
    fun `healthy charging is ok and reports min and median`() {
        val hist = Histogram.EMPTY.add(13.3).add(14.0).add(14.0).add(14.1)
        val ok = digest().copy(voltageSensor = ELM_VOLTAGE, runningVoltage = aggOf(13.3, 14.0, 14.0, 14.1), runningVoltageHist = hist)
        val low = VoltageRule.evaluate(input(listOf(ok))).first { it.ruleId == "voltage-low" }
        assertEquals(Severity.OK, low.severity)
        val text = low.evidence.joinToString { it.value }
        assertTrue(text, text.contains("13,3") && text.contains("14,0"))
        assertEquals(Severity.NO_DATA, VoltageRule.evaluate(input(listOf(digest()))).first().severity)
    }

    @Test
    fun `adapter voltage finding warns that the adapter reads lower`() {
        val hist = (1..199).fold(Histogram.EMPTY) { h, _ -> h.add(12.8) }.add(10.1)
        val low = digest().copy(voltageSensor = ELM_VOLTAGE, runningVoltage = aggOf(10.1, 12.8), runningVoltageHist = hist, lowVoltageMs = 241_000)
        val f = VoltageRule.evaluate(input(listOf(low))).first { it.ruleId == "voltage-low" }
        assertTrue(f.message, f.message.contains("адаптер"))
        // a brief dip (starter, restart) is separated from the steady level
        assertTrue(f.evidence.joinToString(), f.evidence.any { it.label.startsWith("1 %") && it.value == "12,8 В" })
    }

    @Test
    fun `high voltage is a warning`() {
        val high = digest().copy(voltageSensor = ELM_VOLTAGE, runningVoltage = aggOf(15.2), highVoltageMs = 20_000)
        assertEquals(Severity.WARNING, VoltageRule.evaluate(input(listOf(high))).first { it.ruleId == "voltage-high" }.severity)
    }

    // --- fuel trims ---
    @Test
    fun `long term trims beyond 10 percent on both banks`() {
        val d = digest().copy(warmTrims = mapOf(LTFT1 to aggOf(12.5, 12.5), LTFT2 to aggOf(12.5, 12.5), STFT2 to aggOf(4.7, 24.2, 15.0)))
        val f = FuelTrimRule.evaluate(input(listOf(d)))
        val lt = f.first { it.ruleId == "fuel-trim-long" }
        assertEquals(Severity.WARNING, lt.severity)
        assertTrue(lt.message, lt.message.contains("банк 1") && lt.message.contains("банк 2"))
        assertTrue(lt.evidence.any { it.value.contains("+12,5") })
        // few samples: sum 12.5+14.6 > 25, but no CRITICAL
        assertEquals(Severity.WARNING, f.first { it.ruleId == "fuel-trim-total" }.severity)
        assertEquals(Severity.OK, f.first { it.ruleId == "fuel-trim-bank-asymmetry" }.severity)
    }

    @Test
    fun `total trim above 25 percent with enough samples is critical`() {
        val many = DoubleArray(20) { 15.0 }
        val d = digest().copy(warmTrims = mapOf(LTFT1 to aggOf(*many), STFT1 to aggOf(*many)))
        assertEquals(Severity.CRITICAL, FuelTrimRule.evaluate(input(listOf(d))).first { it.ruleId == "fuel-trim-total" }.severity)
    }

    @Test
    fun `bank asymmetry above 8 percent`() {
        val d = digest().copy(warmTrims = mapOf(LTFT1 to aggOf(1.0), LTFT2 to aggOf(9.5)))
        val f = FuelTrimRule.evaluate(input(listOf(d)))
        assertEquals(Severity.WARNING, f.first { it.ruleId == "fuel-trim-bank-asymmetry" }.severity)
        assertEquals(Severity.OK, f.first { it.ruleId == "fuel-trim-long" }.severity)
    }

    @Test
    fun `latest session decides long term trim, older readings do not dilute it`() {
        // December: LTFT1 −8.6 %; May: LTFT1/LTFT2 +12.5 % — the ECU now adds fuel on both banks
        val dec = digest(1).copy(warmTrims = mapOf(LTFT1 to aggOf(-8.6)))
        val may = digest(2).copy(warmTrims = mapOf(LTFT1 to aggOf(12.5, 12.5), LTFT2 to aggOf(12.5, 12.5, 12.5)))
        val lt = FuelTrimRule.evaluate(input(listOf(dec, may))).first { it.ruleId == "fuel-trim-long" }
        assertEquals(Severity.WARNING, lt.severity)
        assertTrue(lt.message, lt.message.contains("банк 1 +12,5 %") && lt.message.contains("банк 2 +12,5 %"))
        assertTrue(lt.evidence.joinToString { it.value }, lt.evidence.any { it.value.contains("−8,6") || it.value.contains("-8,6") })
        assertEquals(listOf(2L), lt.sessionIds)
    }

    @Test
    fun `no warm trims is no data`() {
        assertTrue(FuelTrimRule.evaluate(input(listOf(digest()))).all { it.severity == Severity.NO_DATA })
    }

    // --- MAF at idle ---
    @Test
    fun `maf rule asks for engine volume instead of guessing`() {
        val d = digest().copy(warmIdleMaf = aggOf(3.0, 3.2, 3.1, 3.0, 3.1, 3.2))
        val noVolume = MafIdleRule.only(input(listOf(d), car = CarProfile(name = "c", brand = "Kia", year = 2005)))
        assertEquals(Severity.NO_DATA, noVolume.severity)
        assertTrue(noVolume.message, noVolume.message.contains("объём двигателя") && noVolume.message.contains("Автомобиль"))
        val low = MafIdleRule.only(input(listOf(d), car = CarProfile(name = "c", engineVolumeL = 3.5)))
        assertEquals(Severity.WARNING, low.severity) // ~0.89 g/s per liter
        val ok = MafIdleRule.only(input(listOf(digest().copy(warmIdleMaf = aggOf(4.0, 4.0, 4.0, 4.0, 4.0))), car = CarProfile(name = "c", engineVolumeL = 3.5)))
        assertEquals(Severity.OK, ok.severity)
        assertEquals(Severity.NO_DATA, MafIdleRule.only(input(listOf(digest()), car = CarProfile(name = "c", engineVolumeL = 3.5))).severity)
    }

    // --- MIL ---
    @Test
    fun `distance with MIL on and its growth`() {
        val a = digest(1).copy(milDistanceKm = aggOf(0.0))
        val b = digest(2).copy(milDistanceKm = aggOf(700.0, 753.0))
        val f = MilDistanceRule.only(input(listOf(a, b)))
        assertEquals(Severity.WARNING, f.severity)
        assertTrue(f.message, f.message.contains("753"))
        assertEquals(listOf(2L), f.sessionIds)
        assertEquals(Severity.OK, MilDistanceRule.only(input(listOf(a))).severity)
        assertEquals(Severity.NO_DATA, MilDistanceRule.only(input(listOf(digest()))).severity)
    }

    // --- warm-up ---
    @Test
    fun `warm-up getting longer is reported as a trend`() {
        val old = (1L..3L).map { digest(it).copy(coldStartC = 10.0, warmupMs = 600_000) }
        val new = (4L..6L).map { digest(it).copy(coldStartC = 10.0, warmupMs = 1_000_000) }
        assertEquals(Severity.INFO, WarmupTrendRule.only(input(old + new)).severity)
        assertEquals(Severity.OK, WarmupTrendRule.only(input(old + old.map { it.copy(sessionId = it.sessionId + 3, startedAtMs = it.startedAtMs + 3 * day) })).severity)
        assertEquals(Severity.NO_DATA, WarmupTrendRule.only(input(old)).severity)
    }

    // --- trouble codes ---
    @Test
    fun `dtc groups, repeats and singles`() {
        val events = listOf(
            DtcEvent("P0107", DtcKind.STORED, ms(2025, 12, 16)),
            DtcEvent("P0107", DtcKind.STORED, ms(2026, 1, 4)),
            DtcEvent("P1847", DtcKind.PENDING, ms(2026, 1, 4)),
            DtcEvent("P0171", DtcKind.STORED, ms(2026, 5, 1)),
            DtcEvent("P0174", DtcKind.STORED, ms(2026, 5, 1)),
            DtcEvent("P0171", DtcKind.STORED, ms(2026, 5, 8)),
            DtcEvent("P0174", DtcKind.STORED, ms(2026, 5, 8)),
            DtcEvent("P0113", DtcKind.STORED, ms(2026, 5, 8)),
            DtcEvent("P0102", DtcKind.STORED, ms(2026, 5, 8)),
            // re-reading the same code an hour later — same event
            DtcEvent("P0102", DtcKind.STORED, ms(2026, 5, 8) + 3_600_000),
            DtcEvent("P0300", DtcKind.STORED, ms(2026, 2, 1)),
            DtcEvent("P0300", DtcKind.STORED, ms(2026, 4, 1)),
        )
        val f = DtcRule.evaluate(input(dtc = events)).associateBy { it.ruleId }
        val air = f.getValue("dtc-air-sensors")
        assertEquals(Severity.WARNING, air.severity)
        assertTrue(air.message, air.message.contains("P0102") && air.message.contains("P0107") && air.message.contains("P0113"))
        assertTrue(air.evidence.any { it.label == "P0107" && it.value.startsWith("2 ") })
        assertTrue(air.evidence.any { it.label == "P0102" && it.value.startsWith("1 ") })
        val mix = f.getValue("dtc-mixture")
        assertEquals(Severity.WARNING, mix.severity)
        assertTrue(mix.message.contains("P0171") && mix.message.contains("P0174"))
        // P0300 twice, but 2 months apart — not a "repeat within 30 days"; P1847 — once
        val single = f.getValue("dtc-other")
        assertEquals(Severity.INFO, single.severity)
        assertTrue(single.message.contains("P1847") && single.message.contains("P0300"))
        assertFalse(f.containsKey("dtc-repeat"))
    }

    @Test
    fun `mixture finding names only the banks that have codes`() {
        fun mixture(vararg codes: String) = DtcRule.evaluate(
            input(dtc = codes.flatMap { listOf(DtcEvent(it, DtcKind.STORED, ms(2026, 5, 1)), DtcEvent(it, DtcKind.STORED, ms(2026, 5, 8))) }),
        ).single { it.ruleId == "dtc-mixture" }

        val bank1 = mixture("P0171")
        assertEquals("Смесь (P0171)", bank1.title)
        assertTrue(bank1.message, bank1.message.contains("бедная") && bank1.message.contains("банке 1"))
        assertFalse(bank1.message, bank1.message.contains("обоих"))

        val both = mixture("P0171", "P0174")
        assertEquals("Смесь (P0171/P0174)", both.title)
        assertTrue(both.message, both.message.contains("бедная") && both.message.contains("обоих банках"))

        val rich2 = mixture("P0175")
        assertTrue(rich2.message, rich2.message.contains("богатая") && rich2.message.contains("банке 2"))
        assertFalse(rich2.message, rich2.message.contains("подсос"))
    }

    @Test
    fun `repeat within 30 days outside groups`() {
        val events = listOf(DtcEvent("P0300", DtcKind.STORED, ms(2026, 2, 1)), DtcEvent("P0300", DtcKind.STORED, ms(2026, 2, 20)))
        val f = DtcRule.evaluate(input(dtc = events))
        assertEquals(Severity.WARNING, f.single { it.ruleId == "dtc-repeat" }.severity)
    }

    @Test
    fun `no dtc history is ok`() {
        assertEquals(Severity.OK, DtcRule.only(input()).severity)
    }

    // --- engine ---
    @Test
    fun `engine runs all rules and sorts problems first`() {
        val d = digest().copy(coolant = aggOf(112.0), overheatWarnMs = 40_000, overheatCritMs = 40_000)
        val all = AnalyticsEngine.evaluate(input(listOf(d)))
        assertEquals(Severity.CRITICAL, all.first().severity)
        assertTrue(all.map { it.ruleId }.containsAll(listOf("coolant-overheat", "voltage-low", "fuel-trim-long", "maf-idle", "mil-distance", "warmup-trend")))
        assertTrue(all.zipWithNext().all { (a, b) -> a.severity.rank >= b.severity.rank })
    }
}

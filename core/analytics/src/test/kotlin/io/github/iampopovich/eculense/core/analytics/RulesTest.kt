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
    private fun en(t: FindingText) = FindingEnglish.render(t, zone)
    private fun msg(f: Finding) = FindingEnglish.message(f, zone)

    // --- overheating ---
    @Test
    fun `overheat above 110 for 30 s is critical, 105 is warning, 106 peak is ok`() {
        val crit = CoolantOverheatRule.only(input(listOf(digest().copy(coolant = aggOf(112.0), overheatWarnMs = 40_000, overheatCritMs = 40_000))))
        assertEquals(Severity.CRITICAL, crit.severity)
        assertEquals(listOf(1L), crit.sessionIds)
        // typed text: key + numeric params, rendered per locale by the UI
        assertEquals(FindingText(FindingKey.COOLANT_OVERHEAT, FindingArg.Count(1), FindingArg.Num(110.0, 0), FindingArg.Count(30)), crit.message.single())
        assertEquals("Coolant stayed at ≥ 110 °C for longer than 30 s in 1 recording.", msg(crit))
        val warn = CoolantOverheatRule.only(input(listOf(digest().copy(coolant = aggOf(107.0), overheatWarnMs = 31_000))))
        assertEquals(Severity.WARNING, warn.severity)
        val ok = CoolantOverheatRule.only(input(listOf(digest().copy(coolant = aggOf(90.0, 106.0), overheatWarnMs = 5_000))))
        assertEquals(Severity.OK, ok.severity)
        assertTrue(ok.evidence.any { en(it.value).contains("106") })
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
        val text = low.evidence.joinToString { en(it.value) }
        assertTrue(text, text.contains("13.3") && text.contains("14.0"))
        assertEquals(Severity.NO_DATA, VoltageRule.evaluate(input(listOf(digest()))).first().severity)
    }

    @Test
    fun `adapter voltage finding warns that the adapter reads lower`() {
        val hist = (1..199).fold(Histogram.EMPTY) { h, _ -> h.add(12.8) }.add(10.1)
        val low = digest().copy(voltageSensor = ELM_VOLTAGE, runningVoltage = aggOf(10.1, 12.8), runningVoltageHist = hist, lowVoltageMs = 241_000)
        val f = VoltageRule.evaluate(input(listOf(low))).first { it.ruleId == "voltage-low" }
        assertTrue(msg(f), f.message.any { it.key == FindingKey.VOLTAGE_ADAPTER_NOTE })
        // a brief dip (starter, restart) is separated from the steady level
        assertTrue(f.evidence.joinToString(), f.evidence.any { it.label.key == FindingKey.EVIDENCE_VOLTAGE_P1 && en(it.value) == "12.8 V" })
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
        assertTrue(msg(lt), msg(lt).contains("bank 1") && msg(lt).contains("bank 2"))
        assertTrue(lt.evidence.any { en(it.value).contains("+12.5") })
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
        assertTrue(msg(lt), msg(lt).contains("bank 1 +12.5 %") && msg(lt).contains("bank 2 +12.5 %"))
        assertTrue(lt.evidence.joinToString { en(it.value) }, lt.evidence.any { en(it.value).contains("-8.6") })
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
        assertEquals(FindingKey.MAF_NO_VOLUME, noVolume.message.first().key)
        assertTrue(msg(noVolume), msg(noVolume).contains("engine displacement") && msg(noVolume).contains("Measured: 3.1 g/s"))
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
        assertTrue(msg(f), msg(f).contains("753"))
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
        assertTrue(msg(air), msg(air).contains("P0102") && msg(air).contains("P0107") && msg(air).contains("P0113"))
        assertTrue(air.evidence.any { it.rawLabel == "P0107" && it.value.key == FindingKey.DTC_OCCURRENCES && it.value.quantity == 2 })
        assertTrue(air.evidence.any { it.rawLabel == "P0102" && en(it.value).startsWith("1 time: ") })
        val mix = f.getValue("dtc-mixture")
        assertEquals(Severity.WARNING, mix.severity)
        assertTrue(msg(mix).contains("P0171") && msg(mix).contains("P0174"))
        // P0300 twice, but 2 months apart — not a "repeat within 30 days"; P1847 — once
        val single = f.getValue("dtc-other")
        assertEquals(Severity.INFO, single.severity)
        assertTrue(msg(single).contains("P1847") && msg(single).contains("P0300"))
        assertTrue(single.evidence.any { it.rawLabel == "P1847" && it.value.key == FindingKey.DTC_OCCURRENCES_PENDING })
        assertFalse(f.containsKey("dtc-repeat"))
    }

    @Test
    fun `mixture finding names only the banks that have codes`() {
        fun mixture(vararg codes: String) = DtcRule.evaluate(
            input(dtc = codes.flatMap { listOf(DtcEvent(it, DtcKind.STORED, ms(2026, 5, 1)), DtcEvent(it, DtcKind.STORED, ms(2026, 5, 8))) }),
        ).single { it.ruleId == "dtc-mixture" }

        val bank1 = mixture("P0171")
        assertEquals("Mixture (P0171)", en(bank1.title))
        assertTrue(msg(bank1), msg(bank1).contains("lean") && msg(bank1).contains("on bank 1"))
        assertFalse(msg(bank1), msg(bank1).contains("both"))

        val both = mixture("P0171", "P0174")
        assertEquals("Mixture (P0171/P0174)", en(both.title))
        assertTrue(msg(both), msg(both).contains("lean") && msg(both).contains("on both banks"))

        val rich2 = mixture("P0175")
        assertTrue(msg(rich2), msg(rich2).contains("rich") && msg(rich2).contains("on bank 2"))
        assertFalse(msg(rich2), msg(rich2).contains("air leak"))
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

    // --- typed text ---
    @Test
    fun `english renderer uses plural forms, locale-neutral numbers and dates`() {
        val two = FindingText(FindingKey.VOLTAGE_HIGH, FindingArg.Count(2), FindingArg.Num(14.8))
        assertEquals("Voltage above 14.8 V in 2 recordings — the voltage regulator may be faulty.", en(two))
        val one = two.copy(args = listOf(FindingArg.Count(1), FindingArg.Num(14.8)))
        assertEquals("Voltage above 14.8 V in 1 recording — the voltage regulator may be faulty.", en(one))
        assertEquals("1%% of samples below".replace("%%", "%"), en(FindingText(FindingKey.EVIDENCE_VOLTAGE_P1)))
        val range = FindingText(FindingKey.MINUTES_IN_RANGE, FindingArg.Count(12), FindingArg.DateRange(ms(2026, 5, 1), ms(2026, 5, 8)))
        assertEquals("12 min (1 May 2026 – 8 May 2026)", en(range))
        assertEquals("P0171", en(FindingText.raw("P0171")))
    }

    @Test
    fun `every plural key takes its quantity as the first integer argument`() {
        val plural = FindingKey.entries.filter { it.isPlural }
        assertTrue(plural.isNotEmpty())
        plural.forEach { k -> assertTrue(k.name, k.en.contains("%1\$d") && k.enOne!!.contains("%1\$d")) }
    }

    @Test
    fun `legacy findings with raw text are recognized`() {
        val legacy = Finding("coolant-overheat", Severity.OK, FindingText.raw("old title"), FindingText.raw("old message"))
        assertTrue(legacy.isLegacyText)
        assertEquals("old message", msg(legacy))
        assertFalse(CoolantOverheatRule.only(input()).isLegacyText)
    }
}

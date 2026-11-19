package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.analytics.FindingKey as K
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.DtcKind
import java.time.ZoneId
import kotlin.math.abs

/** rank is for sorting: dangerous first, then «no data», «normal» last. */
enum class Severity(val rank: Int) { OK(0), NO_DATA(1), INFO(2), WARNING(3), CRITICAL(4) }

/**
 * Rule result. Numbers in message/evidence are computed by code; the AI only explains them.
 * OK and NO_DATA are results too: "checked, normal" and "nothing to check with" (rather than a false finding).
 * Text is typed ([FindingText]): the UI renders it from string resources, the AI gets English via [FindingEnglish].
 * [message] is a list of sentences joined with a space.
 */
data class Finding(
    val ruleId: String,
    val severity: Severity,
    val title: FindingText,
    val message: List<FindingText>,
    val evidence: List<Evidence> = emptyList(),
    val sessionIds: List<Long> = emptyList(),
    val fromMs: Long? = null,
    val toMs: Long? = null,
) {
    constructor(
        ruleId: String, severity: Severity, title: FindingText, message: FindingText,
        evidence: List<Evidence> = emptyList(), sessionIds: List<Long> = emptyList(), fromMs: Long? = null, toMs: Long? = null,
    ) : this(ruleId, severity, title, listOf(message), evidence, sessionIds, fromMs, toMs)

    /** Stored by a version before localization: raw (Russian) text; a recompute replaces it with typed text. */
    val isLegacyText: Boolean get() = title.key == K.RAW
}

/** Trouble code event: live snapshot or imported Car Scanner event. */
data class DtcEvent(val code: String, val kind: DtcKind, val atMs: Long)

data class AnalysisInput(
    val car: CarProfile?,
    val sessions: List<SessionDigest>,
    val dtcEvents: List<DtcEvent>,
    val nowMs: Long,
    val zone: ZoneId = ZoneId.systemDefault(),
)

interface AnalyticsRule {
    /** All ruleIds the rule can return (for «no data» and links). */
    val ids: List<String>
    fun evaluate(input: AnalysisInput): List<Finding>
}

object AnalyticsEngine {
    val RULES: List<AnalyticsRule> = listOf(CoolantOverheatRule, VoltageRule, FuelTrimRule, MafIdleRule, DtcRule, MilDistanceRule, WarmupTrendRule)

    fun evaluate(input: AnalysisInput, rules: List<AnalyticsRule> = RULES): List<Finding> =
        rules.flatMap { it.evaluate(input) }.sortedByDescending { it.severity.rank }
}

/** Short builders for typed text. */
private fun t(key: K, vararg args: FindingArg) = FindingText(key, args.toList())
private fun num(v: Double, decimals: Int = 1) = FindingArg.Num(v, decimals)
private fun signed(v: Double, decimals: Int = 1) = FindingArg.Num(v, decimals, signed = true)
private fun cnt(n: Int) = FindingArg.Count(n)
private fun cnt(n: Long) = FindingArg.Count(n.toInt())
private fun text(t: FindingText?) = FindingArg.Text(t)
private fun ev(label: K, value: FindingText) = Evidence(t(label), value)
private fun countValue(n: Int) = t(K.VALUE_COUNT, cnt(n))
private fun secondsValue(ms: Long) = t(K.VALUE_SECONDS, cnt(ms / 1000))
private fun minutes(ms: Long) = cnt(ms / 60_000)

private fun ok(id: String, title: FindingText, message: FindingText, evidence: List<Evidence> = emptyList()) =
    Finding(id, Severity.OK, title, message, evidence)

private fun noData(id: String, title: FindingText, message: FindingText) = Finding(id, Severity.NO_DATA, title, message)

/** Coolant ≥ 105 °C for over 30 s is WARNING, ≥ 110 °C for over 30 s is CRITICAL. */
object CoolantOverheatRule : AnalyticsRule {
    private const val ID = "coolant-overheat"
    private val TITLE = t(K.TITLE_COOLANT_OVERHEAT)
    override val ids = listOf(ID)

    override fun evaluate(input: AnalysisInput): List<Finding> {
        val withCoolant = input.sessions.filter { it.coolant.count > 0 }
        if (withCoolant.isEmpty()) return listOf(noData(ID, TITLE, t(K.COOLANT_NO_DATA)))
        val max = withCoolant.maxOf { it.coolant.max }
        val crit = withCoolant.filter { it.overheatCritMs >= Thresholds.OVERHEAT_MIN_MS }
        val warn = withCoolant.filter { it.overheatWarnMs >= Thresholds.OVERHEAT_MIN_MS }
        val evidence = listOf(
            ev(K.EVIDENCE_COOLANT_MAX, t(K.VALUE_CELSIUS, num(max))),
            ev(K.EVIDENCE_COOLANT_RECORDINGS, countValue(withCoolant.size)),
        )
        fun hit(severity: Severity, sessions: List<SessionDigest>, limit: Double, longest: (SessionDigest) -> Long) = Finding(
            ID, severity, TITLE,
            t(K.COOLANT_OVERHEAT, cnt(sessions.size), num(limit, 0), cnt(Thresholds.OVERHEAT_MIN_MS / 1000)),
            evidence + ev(K.EVIDENCE_LONGEST, secondsValue(sessions.maxOf(longest))),
            sessionIds = sessions.map { it.sessionId },
            fromMs = sessions.minOf { it.startedAtMs }, toMs = sessions.maxOf { it.startedAtMs },
        )
        return listOf(
            when {
                crit.isNotEmpty() -> hit(Severity.CRITICAL, crit, Thresholds.OVERHEAT_CRIT_C) { it.overheatCritMs }
                warn.isNotEmpty() -> hit(Severity.WARNING, warn, Thresholds.OVERHEAT_WARN_C) { it.overheatWarnMs }
                else -> ok(ID, TITLE, t(K.COOLANT_OK, num(Thresholds.OVERHEAT_WARN_C, 0)), evidence)
            },
        )
    }
}

/** Voltage with the engine running: < 13.2 V for over 60 s and > 14.8 V for over 10 s. */
object VoltageRule : AnalyticsRule {
    private const val LOW = "voltage-low"
    private const val HIGH = "voltage-high"
    private val TITLE_LOW = t(K.TITLE_VOLTAGE_LOW)
    private val TITLE_HIGH = t(K.TITLE_VOLTAGE_HIGH)
    override val ids = listOf(LOW, HIGH)

    override fun evaluate(input: AnalysisInput): List<Finding> {
        val withV = input.sessions.filter { it.runningVoltage.count > 0 }
        if (withV.isEmpty()) {
            val msg = t(K.VOLTAGE_NO_DATA)
            return listOf(noData(LOW, TITLE_LOW, msg), noData(HIGH, TITLE_HIGH, msg))
        }
        val all = withV.map { it.runningVoltage }.merged()
        val hist = withV.map { it.runningVoltageHist }.fold(Histogram.EMPTY) { a, b -> a + b }
        val median = hist.quantile(0.5)
        val sources = withV.mapNotNull { it.voltageSensor }.distinct().map { FindingText(K.VALUE_PLAIN, FindingArg.Sensor(it)) }
        val evidence = listOfNotNull(
            hist.quantile(0.01)?.let { ev(K.EVIDENCE_VOLTAGE_P1, t(K.VALUE_VOLTS, num(it))) },
            median?.let { ev(K.EVIDENCE_MEDIAN, t(K.VALUE_VOLTS, num(it))) },
            ev(K.EVIDENCE_MAXIMUM, t(K.VALUE_VOLTS, num(all.max))),
            ev(K.EVIDENCE_VOLTAGE_MIN, t(K.VALUE_VOLTS, num(all.min))),
            ev(K.EVIDENCE_SOURCE, t(K.VALUE_PLAIN, FindingArg.Join(sources))),
        )
        val low = withV.filter { it.lowVoltageMs >= Thresholds.VOLTAGE_LOW_MIN_MS }
        val high = withV.filter { it.highVoltageMs >= Thresholds.VOLTAGE_HIGH_MIN_MS }
        val adapterNote = if (low.any { it.voltageSensor == SensorIds.ELM_VOLTAGE }) t(K.VOLTAGE_ADAPTER_NOTE) else null
        return listOf(
            if (low.isEmpty()) {
                ok(LOW, TITLE_LOW, t(K.VOLTAGE_LOW_OK, num(Thresholds.VOLTAGE_LOW_V)), evidence)
            } else {
                Finding(
                    LOW, Severity.WARNING, TITLE_LOW,
                    listOfNotNull(t(K.VOLTAGE_LOW, cnt(low.size), num(Thresholds.VOLTAGE_LOW_V)), adapterNote),
                    evidence + ev(K.EVIDENCE_LONGEST, secondsValue(low.maxOf { it.lowVoltageMs })),
                    low.map { it.sessionId }, low.minOf { it.startedAtMs }, low.maxOf { it.startedAtMs },
                )
            },
            if (high.isEmpty()) {
                ok(HIGH, TITLE_HIGH, t(K.VOLTAGE_HIGH_OK, num(Thresholds.VOLTAGE_HIGH_V)), evidence)
            } else {
                Finding(
                    HIGH, Severity.WARNING, TITLE_HIGH,
                    t(K.VOLTAGE_HIGH, cnt(high.size), num(Thresholds.VOLTAGE_HIGH_V)),
                    evidence + ev(K.EVIDENCE_LONGEST, secondsValue(high.maxOf { it.highVoltageMs })),
                    high.map { it.sessionId }, high.minOf { it.startedAtMs }, high.maxOf { it.startedAtMs },
                )
            },
        )
    }
}

/** Fuel trims on a warm engine. Sign «+» means the ECU adds fuel (lean mixture). */
object FuelTrimRule : AnalyticsRule {
    private const val LONG = "fuel-trim-long"
    private const val TOTAL = "fuel-trim-total"
    private const val ASYM = "fuel-trim-bank-asymmetry"
    private val TITLE_LONG = t(K.TITLE_FUEL_TRIM_LONG)
    private val TITLE_TOTAL = t(K.TITLE_FUEL_TRIM_TOTAL)
    private val TITLE_ASYM = t(K.TITLE_BANK_ASYMMETRY)
    override val ids = listOf(LONG, TOTAL, ASYM)

    /** ltft/stft come from the latest recording with warm LTFT for the bank: ECU adaptation changes, old months don't describe it. */
    private data class Bank(val n: Int, val stft: Agg, val ltft: Agg, val ltftAll: Agg, val atMs: Long, val sessions: List<Long>)

    override fun evaluate(input: AnalysisInput): List<Finding> {
        fun bank(n: Int, stftId: String, ltftId: String): Bank? {
            val latest = input.sessions.filter { (it.warmTrims[ltftId]?.count ?: 0) > 0 }.maxByOrNull { it.startedAtMs } ?: return null
            val stft = latest.warmTrims[stftId]?.takeIf { it.count > 0 } ?: Agg.EMPTY
            return Bank(n, stft, latest.warmTrims.getValue(ltftId), input.sessions.mapNotNull { it.warmTrims[ltftId] }.merged(), latest.startedAtMs, listOf(latest.sessionId))
        }
        val withLt = listOfNotNull(bank(1, SensorIds.STFT1, SensorIds.LTFT1), bank(2, SensorIds.STFT2, SensorIds.LTFT2))
        if (withLt.isEmpty()) {
            val msg = t(K.FUEL_TRIM_NO_DATA)
            return listOf(noData(LONG, TITLE_LONG, msg), noData(TOTAL, TITLE_TOTAL, msg), noData(ASYM, TITLE_ASYM, msg))
        }
        val few = { b: Bank -> b.ltft.count < Thresholds.MIN_TRIM_SAMPLES }
        val evidence = withLt.flatMap { b ->
            val all = if (b.ltftAll.count > b.ltft.count) t(K.LTFT_ALL_TIME, signed(b.ltftAll.min), signed(b.ltftAll.max)) else null
            listOfNotNull(
                Evidence(
                    t(K.EVIDENCE_LTFT_BANK, cnt(b.n)),
                    t(K.LTFT_VALUE, signed(b.ltft.mean!!), FindingArg.Date(b.atMs), cnt(b.ltft.count), text(all)),
                ),
                b.stft.mean?.let { Evidence(t(K.EVIDENCE_STFT_BANK, cnt(b.n)), t(K.STFT_VALUE, signed(it), signed(b.stft.max), cnt(b.stft.count))) },
            )
        }
        val sessions = withLt.flatMap { it.sessions }.distinct()
        val fewNote = if (withLt.any(few)) t(K.FEW_SAMPLES_NOTE) else null

        val out = withLt.filter { abs(it.ltft.mean!!) > Thresholds.LTFT_LIMIT_PCT }
        val long = if (out.isEmpty()) {
            ok(LONG, TITLE_LONG, t(K.LTFT_OK, num(Thresholds.LTFT_LIMIT_PCT, 0)), evidence)
        } else {
            val parts = out.map { t(K.BANK_PERCENT, cnt(it.n), signed(it.ltft.mean!!)) }
            val lean = out.all { it.ltft.mean!! > 0 }
            Finding(
                LONG, Severity.WARNING, TITLE_LONG,
                listOfNotNull(
                    t(K.LTFT_OUT, num(Thresholds.LTFT_LIMIT_PCT, 0), FindingArg.Join(parts)),
                    if (lean) t(K.LEAN_NOTE) else null,
                    fewNote,
                ),
                evidence, sessions,
            )
        }

        val totals = withLt.filter { it.stft.count > 0 }.map { it to it.stft.mean!! + it.ltft.mean!! }
        val total = when {
            totals.isEmpty() -> noData(TOTAL, TITLE_TOTAL, t(K.TOTAL_TRIM_NO_DATA))
            totals.none { abs(it.second) > Thresholds.TOTAL_TRIM_CRIT_PCT } ->
                ok(TOTAL, TITLE_TOTAL, t(K.TOTAL_TRIM_OK, num(Thresholds.TOTAL_TRIM_CRIT_PCT, 0)), evidence)
            else -> {
                val bad = totals.filter { abs(it.second) > Thresholds.TOTAL_TRIM_CRIT_PCT }
                val enough = bad.all { (b, _) -> b.ltft.count >= Thresholds.MIN_TRIM_SAMPLES && b.stft.count >= Thresholds.MIN_TRIM_SAMPLES }
                Finding(
                    TOTAL, if (enough) Severity.CRITICAL else Severity.WARNING, TITLE_TOTAL,
                    listOfNotNull(
                        t(K.TOTAL_TRIM_OUT, num(Thresholds.TOTAL_TRIM_CRIT_PCT, 0), FindingArg.Join(bad.map { (b, v) -> t(K.BANK_PERCENT, cnt(b.n), signed(v)) })),
                        if (enough) null else t(K.PRELIMINARY_NOTE),
                    ),
                    evidence, sessions,
                )
            }
        }

        val asym = if (withLt.size < 2) {
            noData(ASYM, TITLE_ASYM, t(K.BANK_ASYMMETRY_NO_DATA))
        } else {
            val diff = abs(withLt[0].ltft.mean!! - withLt[1].ltft.mean!!)
            val ev = evidence + ev(K.EVIDENCE_LTFT_DIFFERENCE, t(K.VALUE_PERCENT, num(diff)))
            if (diff > Thresholds.BANK_ASYMMETRY_PCT) {
                Finding(ASYM, Severity.WARNING, TITLE_ASYM, listOfNotNull(t(K.BANK_ASYMMETRY, num(diff)), fewNote), ev, sessions)
            } else {
                ok(ASYM, TITLE_ASYM, t(K.BANK_ASYMMETRY_OK, num(diff)), ev)
            }
        }
        return listOf(long, total, asym)
    }
}

/** MAF at warm idle relative to engine displacement from the profile. */
object MafIdleRule : AnalyticsRule {
    private const val ID = "maf-idle"
    private val TITLE = t(K.TITLE_MAF_IDLE)
    override val ids = listOf(ID)

    override fun evaluate(input: AnalysisInput): List<Finding> {
        val maf = input.sessions.map { it.warmIdleMaf }.merged()
        val volume = input.car?.engineVolumeL?.takeIf { it > 0 }
        if (volume == null) {
            val measured = maf.mean?.let { t(K.MAF_MEASURED, num(it)) }
            return listOf(Finding(ID, Severity.NO_DATA, TITLE, listOfNotNull(t(K.MAF_NO_VOLUME), measured)))
        }
        if (maf.count < Thresholds.MAF_IDLE_MIN_SAMPLES) {
            return listOf(noData(ID, TITLE, t(K.MAF_NO_SAMPLES)))
        }
        val perL = maf.mean!! / volume
        val evidence = listOf(
            ev(K.EVIDENCE_MAF_WARM_IDLE, t(K.MAF_VALUE, num(maf.mean!!), cnt(maf.count))),
            ev(K.EVIDENCE_ENGINE_VOLUME, t(K.VALUE_LITERS, num(volume))),
            ev(K.EVIDENCE_PER_LITER, t(K.PER_LITER_VALUE, num(perL, 2), num(Thresholds.MAF_IDLE_MIN_G_PER_L), num(Thresholds.MAF_IDLE_MAX_G_PER_L))),
        )
        val sessions = input.sessions.filter { it.warmIdleMaf.count > 0 }.map { it.sessionId }
        return listOf(
            when {
                perL < Thresholds.MAF_IDLE_MIN_G_PER_L -> Finding(ID, Severity.WARNING, TITLE, t(K.MAF_LOW, num(perL, 2)), evidence, sessions)
                perL > Thresholds.MAF_IDLE_MAX_G_PER_L -> Finding(ID, Severity.INFO, TITLE, t(K.MAF_HIGH, num(perL, 2)), evidence, sessions)
                else -> ok(ID, TITLE, t(K.MAF_OK, num(perL, 2)), evidence)
            },
        )
    }
}

/** Distance driven with Check Engine on (PID 01.21) and its growth. */
object MilDistanceRule : AnalyticsRule {
    private const val ID = "mil-distance"
    private val TITLE = t(K.TITLE_MIL_DISTANCE)
    override val ids = listOf(ID)

    override fun evaluate(input: AnalysisInput): List<Finding> {
        val with = input.sessions.filter { it.milDistanceKm.count > 0 }.sortedBy { it.startedAtMs }
        if (with.isEmpty()) return listOf(noData(ID, TITLE, t(K.MIL_NO_DATA)))
        val lit = with.filter { it.milDistanceKm.max > 0 }
        if (lit.isEmpty()) return listOf(ok(ID, TITLE, t(K.MIL_OK), listOf(ev(K.EVIDENCE_PID21_RECORDINGS, countValue(with.size)))))
        val first = lit.first().milDistanceKm.min.takeIf { it > 0 } ?: lit.first().milDistanceKm.max
        val last = lit.maxOf { it.milDistanceKm.max }
        val evidence = listOf(
            ev(K.EVIDENCE_MIL_DISTANCE, t(K.VALUE_KM, num(last, 0))),
            ev(K.EVIDENCE_FIRST_NONZERO, t(K.KM_ON_DATE, num(first, 0), FindingArg.Date(lit.first().startedAtMs))),
            ev(K.EVIDENCE_MIL_RECORDINGS, countValue(lit.size)),
        )
        val growth = if (last > first) t(K.MIL_GROWTH, num(first, 0), num(last, 0)) else null
        return listOf(
            Finding(
                ID, Severity.WARNING, TITLE,
                listOfNotNull(t(K.MIL_DRIVEN, num(last, 0)), growth, t(K.MIL_FIX)),
                evidence, lit.map { it.sessionId }, lit.first().startedAtMs, lit.last().startedAtMs,
            ),
        )
    }
}

/** Warm-up time to 80 °C from a cold start: trend between early and late trips. */
object WarmupTrendRule : AnalyticsRule {
    private const val ID = "warmup-trend"
    private val TITLE = t(K.TITLE_WARMUP)
    override val ids = listOf(ID)

    private fun median(v: List<Long>): Long = v.sorted()[(v.size - 1) / 2]

    override fun evaluate(input: AnalysisInput): List<Finding> {
        val starts = input.sessions.filter { it.warmupMs != null }.sortedBy { it.startedAtMs }
        val min = Thresholds.WARMUP_MIN_STARTS_PER_HALF
        if (starts.size < 2 * min) {
            return listOf(noData(ID, TITLE, t(K.WARMUP_FEW_STARTS, cnt(starts.size), cnt(2 * min))))
        }
        val half = starts.size / 2
        val early = median(starts.take(half).map { it.warmupMs!! })
        val late = median(starts.drop(starts.size - half).map { it.warmupMs!! })
        val evidence = listOf(
            ev(K.EVIDENCE_WARMUP_EARLY, t(K.MINUTES_IN_RANGE, minutes(early), FindingArg.DateRange(starts.first().startedAtMs, starts[half - 1].startedAtMs))),
            ev(K.EVIDENCE_WARMUP_LATE, t(K.MINUTES_IN_RANGE, minutes(late), FindingArg.DateRange(starts[starts.size - half].startedAtMs, starts.last().startedAtMs))),
            ev(K.EVIDENCE_COLD_STARTS, countValue(starts.size)),
        )
        return listOf(
            if (late > early * Thresholds.WARMUP_TREND_RATIO) {
                Finding(
                    ID, Severity.INFO, TITLE,
                    t(K.WARMUP_LONGER, minutes(early), minutes(late)),
                    evidence, starts.drop(starts.size - half).map { it.sessionId },
                )
            } else {
                ok(ID, TITLE, t(K.WARMUP_OK), evidence)
            },
        )
    }
}

/** Trouble codes: groups of related codes, repeats within 30 days, the rest as reference. */
object DtcRule : AnalyticsRule {
    /** [title] and [explain] receive the group's codes found in history: the text must not mention what isn't there. */
    private data class Group(
        val id: String,
        val title: (List<String>) -> FindingText,
        val codes: (String) -> Boolean,
        val explain: (List<String>) -> FindingText,
    )

    /** P0171/P0174: lean mixture on banks 1/2, P0172/P0175: rich. */
    private val MIXTURE_BANK = mapOf("P0171" to 1, "P0172" to 1, "P0174" to 2, "P0175" to 2)
    private val LEAN = setOf("P0171", "P0174")

    private fun mixtureExplain(codes: List<String>): FindingText {
        fun banks(of: List<String>): FindingText {
            val b = of.mapNotNull(MIXTURE_BANK::get).toSortedSet()
            return if (b.size == 2) t(K.BANKS_BOTH) else t(K.BANK_ONE, cnt(b.single()))
        }
        val lean = codes.filter { it in LEAN }
        val rich = codes.filter { it !in LEAN }
        val parts = buildList {
            if (lean.isNotEmpty()) add(t(K.MIXTURE_LEAN, text(banks(lean))))
            if (rich.isNotEmpty()) add(t(K.MIXTURE_RICH, text(banks(rich))))
        }
        return t(K.VALUE_PLAIN, FindingArg.Join(parts, "; "))
    }

    private val GROUPS = listOf(
        Group("dtc-air-sensors", { t(K.TITLE_DTC_AIR_SENSORS) }, { c -> c.length == 5 && c.startsWith("P01") && c.drop(3).toIntOrNull()?.let { it in 0..14 } == true },
            { t(K.DTC_AIR_SENSORS_EXPLAIN) }),
        Group("dtc-mixture", { codes -> t(K.TITLE_DTC_MIXTURE, FindingArg.Raw(codes.joinToString("/"))) }, { c -> c in MIXTURE_BANK }, ::mixtureExplain),
    )
    override val ids = GROUPS.map { it.id } + listOf("dtc-repeat", "dtc-other", "dtc-history")

    /** Reads of the same code more often than once per 6 hours count as one event. */
    private fun occurrences(events: List<DtcEvent>): Map<String, List<DtcEvent>> =
        events.groupBy { it.code }.mapValues { (_, list) ->
            list.sortedBy { it.atMs }.fold(mutableListOf<DtcEvent>()) { acc, e ->
                if (acc.isEmpty() || e.atMs - acc.last().atMs >= Thresholds.DTC_SAME_EVENT_MS) acc += e
                acc
            }
        }

    private fun repeated(list: List<DtcEvent>) = list.zipWithNext().any { (a, b) -> b.atMs - a.atMs <= Thresholds.DTC_REPEAT_WINDOW_MS }

    override fun evaluate(input: AnalysisInput): List<Finding> {
        if (input.dtcEvents.isEmpty()) return listOf(ok("dtc-history", t(K.TITLE_DTC_HISTORY), t(K.DTC_NONE)))
        val occ = occurrences(input.dtcEvents)
        fun evidence(code: String): Evidence {
            val list = occ.getValue(code)
            val key = if (list.all { it.kind == DtcKind.PENDING }) K.DTC_OCCURRENCES_PENDING else K.DTC_OCCURRENCES
            return Evidence(FindingText.raw(code), t(key, cnt(list.size), FindingArg.DateRange(list.first().atMs, list.last().atMs)))
        }
        fun summary(codes: List<String>) = FindingArg.Raw(codes.joinToString { "$it×${occ.getValue(it).size}" })
        val used = mutableSetOf<String>()
        val out = mutableListOf<Finding>()
        for (g in GROUPS) {
            val codes = occ.keys.filter(g.codes).sorted()
            if (codes.isEmpty()) continue
            val grouped = codes.size >= 2 || codes.any { occ.getValue(it).size >= 2 }
            if (!grouped) continue
            used += codes
            val all = codes.flatMap { occ.getValue(it) }
            out += Finding(
                g.id, Severity.WARNING, g.title(codes),
                t(K.DTC_GROUP, summary(codes), text(g.explain(codes))),
                codes.map(::evidence), fromMs = all.minOf { it.atMs }, toMs = all.maxOf { it.atMs },
            )
        }
        val repeats = occ.filterKeys { it !in used }.filterValues(::repeated).keys.sorted()
        if (repeats.isNotEmpty()) {
            used += repeats
            out += Finding("dtc-repeat", Severity.WARNING, t(K.TITLE_DTC_REPEAT), t(K.DTC_REPEAT, summary(repeats)), repeats.map(::evidence))
        }
        val rest = occ.keys.filter { it !in used }.sorted()
        if (rest.isNotEmpty()) {
            out += Finding("dtc-other", Severity.INFO, t(K.TITLE_DTC_OTHER), t(K.DTC_OTHER, summary(rest)), rest.map(::evidence))
        }
        return out
    }
}

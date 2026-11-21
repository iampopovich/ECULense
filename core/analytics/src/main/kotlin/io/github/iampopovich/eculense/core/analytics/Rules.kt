package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.analytics.FindingKey as K
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.FreezeFrame
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
    /** R-H: the per-car norms this finding was compared with (shown as «compared with», sent to the AI). */
    val norms: List<Norm> = emptyList(),
) {
    constructor(
        ruleId: String, severity: Severity, title: FindingText, message: FindingText,
        evidence: List<Evidence> = emptyList(), sessionIds: List<Long> = emptyList(), fromMs: Long? = null, toMs: Long? = null,
        norms: List<Norm> = emptyList(),
    ) : this(ruleId, severity, title, listOf(message), evidence, sessionIds, fromMs, toMs, norms)

    /** Stored by a version before localization: raw (Russian) text; a recompute replaces it with typed text. */
    val isLegacyText: Boolean get() = title.key == K.RAW
}

/** Trouble code event: live snapshot or imported Car Scanner event. */
/** [freezeFrame]: B3, the conditions the ECU saved with this code (live reads only). */
data class DtcEvent(val code: String, val kind: DtcKind, val atMs: Long, val freezeFrame: FreezeFrame? = null)

data class AnalysisInput(
    val car: CarProfile?,
    val sessions: List<SessionDigest>,
    val dtcEvents: List<DtcEvent>,
    val nowMs: Long,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** B10: stalls and user marks stored with the car's recordings. */
    val events: List<TripEventFact> = emptyList(),
    /** R-H: the car's norms (stored + generic fallbacks) and engine traits. */
    val norms: CarNorms = CarNorms(car = car),
) {
    val traits: EngineTraits get() = norms.traits
}

/** B10: what happened inside a recording. */
enum class TripEventType { STALL, USER_MARK }

data class TripEventFact(val sessionId: Long, val atMs: Long, val type: TripEventType)

interface AnalyticsRule {
    /** All ruleIds the rule can return (for «no data» and links). */
    val ids: List<String>
    fun evaluate(input: AnalysisInput): List<Finding>
}

object AnalyticsEngine {
    val RULES: List<AnalyticsRule> = listOf(
        CoolantOverheatRule, CoolantPlateauRule, VoltageRule, FuelTrimRule, MafIdleRule, IdleRpmRule, DtcRule, MilDistanceRule, WarmupTrendRule, StallRule,
    )

    fun evaluate(input: AnalysisInput, rules: List<AnalyticsRule> = RULES): List<Finding> =
        rules.flatMap { it.evaluate(input) }.map(::withNorms).sortedByDescending { it.severity.rank }

    /**
     * R-H: every finding compared with norms shows them («compared with …» evidence). Compared only with GENERIC norms:
     * severity capped at WARNING and the message says the norm is not verified for this engine. Class-2 safety findings
     * carry no norms and keep their absolute severity.
     */
    fun withNorms(f: Finding): Finding {
        if (f.norms.isEmpty()) return f
        val generic = f.norms.all { it.isGeneric }
        val flagged = generic && f.severity.rank >= Severity.INFO.rank
        return f.copy(
            severity = if (generic && f.severity.rank > Severity.WARNING.rank) Severity.WARNING else f.severity,
            message = if (flagged) f.message + t(K.GENERIC_NORM_NOTE) else f.message,
            evidence = f.evidence + f.norms.map(NormText::evidence),
        )
    }
}

/** Values of the newest sessions for [key] with [value] per session (newest first, at most [BaselineLearner.RECENT_SESSIONS]). */
private fun recent(sessions: List<SessionDigest>, limit: Int = BaselineLearner.RECENT_SESSIONS, value: (SessionDigest) -> Double?): Pair<Double, List<SessionDigest>>? {
    val r = sessions.sortedByDescending { it.startedAtMs }.mapNotNull { d -> value(d)?.let { d to it } }.take(limit)
    if (r.isEmpty()) return null
    return BaselineLearner.median(r.map { it.second }) to r.map { it.first }
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

/**
 * Voltage with the engine running. Class 2 (absolute, any car): a session median below [Thresholds.LIVE_NOT_CHARGING_V]
 * (the alternator supplies nothing) or above [Thresholds.LIVE_OVERCHARGE_V]. Class 3 (per car): the median of the
 * recent sessions' idle / driving medians against [NormKey.CHARGING_IDLE_V] / [NormKey.CHARGING_DRIVING_V] — a smart
 * alternator's 12.6 V cruise is normal once the car's own baseline says so.
 */
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
        val idleNorm = input.norms.effective(NormKey.CHARGING_IDLE_V)
        val drivingNorm = input.norms.effective(NormKey.CHARGING_DRIVING_V)
        val idle = recent(withV) { BaselineLearner.sessionValue(NormKey.CHARGING_IDLE_V, it) }
        val driving = recent(withV) { BaselineLearner.sessionValue(NormKey.CHARGING_DRIVING_V, it) }
        val evidence = listOfNotNull(
            hist.quantile(0.01)?.let { ev(K.EVIDENCE_VOLTAGE_P1, t(K.VALUE_VOLTS, num(it))) },
            median?.let { ev(K.EVIDENCE_MEDIAN, t(K.VALUE_VOLTS, num(it))) },
            ev(K.EVIDENCE_MAXIMUM, t(K.VALUE_VOLTS, num(all.max))),
            ev(K.EVIDENCE_VOLTAGE_MIN, t(K.VALUE_VOLTS, num(all.min))),
            idle?.let { ev(K.EVIDENCE_VOLTAGE_IDLE, t(K.VALUE_VOLTS, num(it.first))) },
            driving?.let { ev(K.EVIDENCE_VOLTAGE_DRIVING, t(K.VALUE_VOLTS, num(it.first))) },
            ev(K.EVIDENCE_SOURCE, t(K.VALUE_PLAIN, FindingArg.Join(sources))),
        )
        val sessionMedian = { d: SessionDigest -> d.runningVoltageHist.quantile(0.5) }
        val dead = withV.filter { (sessionMedian(it) ?: Double.NaN) < Thresholds.LIVE_NOT_CHARGING_V }
        val over = withV.filter { (sessionMedian(it) ?: Double.NaN) > Thresholds.LIVE_OVERCHARGE_V }
        val adapterNote = if (withV.any { it.voltageSensor == SensorIds.ELM_VOLTAGE }) t(K.VOLTAGE_ADAPTER_NOTE) else null
        fun span(list: List<SessionDigest>) = list.minOf { it.startedAtMs } to list.maxOf { it.startedAtMs }
        val used = listOfNotNull(idleNorm.takeIf { idle != null }, drivingNorm.takeIf { driving != null })

        val lowParts = listOfNotNull(
            idle?.takeIf { idleNorm?.below(it.first) == true }?.let { Triple(t(K.VOLTAGE_AT_IDLE, num(it.first)), idleNorm!!, it.second) },
            driving?.takeIf { drivingNorm?.below(it.first) == true }?.let { Triple(t(K.VOLTAGE_WHILE_DRIVING, num(it.first)), drivingNorm!!, it.second) },
        )
        val highParts = listOfNotNull(
            idle?.takeIf { idleNorm?.above(it.first) == true }?.let { Triple(t(K.VOLTAGE_AT_IDLE, num(it.first)), idleNorm!!, it.second) },
            driving?.takeIf { drivingNorm?.above(it.first) == true }?.let { Triple(t(K.VOLTAGE_WHILE_DRIVING, num(it.first)), drivingNorm!!, it.second) },
        )
        // A drift from the car's own baseline that stays inside the standard regulator window (13.2–14.8 V, the generic
        // band) is worth watching (INFO), not a fault: 14.3 V at idle is a healthy set point even if this car usually
        // sits at 13.9 V (cold days raise the set point by ~10 mV/K). Outside the window, or against the user's own
        // band, it is a WARNING.
        val window = GenericNorms.of(NormKey.CHARGING_IDLE_V, input.car, input.traits)!!
        fun drift(parts: List<Triple<FindingText, Norm, List<SessionDigest>>>, values: List<Double>) =
            parts.all { it.second.source == NormSource.BASELINE } && values.none { window.outside(it) }
        val lowValues = listOfNotNull(idle?.first?.takeIf { idleNorm?.below(it) == true }, driving?.first?.takeIf { drivingNorm?.below(it) == true })
        val highValues = listOfNotNull(idle?.first?.takeIf { idleNorm?.above(it) == true }, driving?.first?.takeIf { drivingNorm?.above(it) == true })
        val low = when {
            dead.isNotEmpty() -> Finding(
                LOW, Severity.WARNING, TITLE_LOW,
                listOfNotNull(t(K.VOLTAGE_NOT_CHARGING, num(Thresholds.LIVE_NOT_CHARGING_V), cnt(dead.size)), adapterNote),
                evidence, dead.map { it.sessionId }, span(dead).first, span(dead).second,
            )
            lowParts.isNotEmpty() -> {
                val sessions = lowParts.flatMap { it.third }.distinctBy { it.sessionId }
                val mild = drift(lowParts, lowValues)
                Finding(
                    LOW, if (mild) Severity.INFO else Severity.WARNING, TITLE_LOW,
                    listOfNotNull(t(if (mild) K.VOLTAGE_DIFFERS_NORM else K.VOLTAGE_LOW_NORM, FindingArg.Join(lowParts.map { it.first })), adapterNote),
                    evidence, sessions.map { it.sessionId }, span(sessions).first, span(sessions).second, norms = lowParts.map { it.second },
                )
            }
            else -> ok(LOW, TITLE_LOW, t(K.VOLTAGE_NORM_OK), evidence).copy(norms = used)
        }
        val high = when {
            over.isNotEmpty() -> Finding(
                HIGH, Severity.WARNING, TITLE_HIGH, t(K.VOLTAGE_OVERCHARGE, num(Thresholds.LIVE_OVERCHARGE_V), cnt(over.size)),
                evidence, over.map { it.sessionId }, span(over).first, span(over).second,
            )
            highParts.isNotEmpty() -> {
                val sessions = highParts.flatMap { it.third }.distinctBy { it.sessionId }
                val mild = drift(highParts, highValues)
                Finding(
                    HIGH, if (mild) Severity.INFO else Severity.WARNING, TITLE_HIGH,
                    t(if (mild) K.VOLTAGE_DIFFERS_NORM else K.VOLTAGE_HIGH_NORM, FindingArg.Join(highParts.map { it.first })),
                    evidence, sessions.map { it.sessionId }, span(sessions).first, span(sessions).second, norms = highParts.map { it.second },
                )
            }
            else -> ok(HIGH, TITLE_HIGH, t(K.VOLTAGE_NORM_OK), evidence).copy(norms = used)
        }
        return listOf(low, high)
    }
}

/**
 * Fuel trims on a warm engine. Sign «+» means the ECU adds fuel (lean mixture). Judged = median of the recent sessions'
 * warm means against the car's norms [NormKey.LTFT_B1]/[NormKey.LTFT_B2], [NormKey.TOTAL_TRIM], [NormKey.BANK_ASYMMETRY].
 * Not judged on a diesel (lean, unthrottled: petrol trim norms do not apply).
 */
object FuelTrimRule : AnalyticsRule {
    private const val LONG = "fuel-trim-long"
    private const val TOTAL = "fuel-trim-total"
    private const val ASYM = "fuel-trim-bank-asymmetry"
    private val TITLE_LONG = t(K.TITLE_FUEL_TRIM_LONG)
    private val TITLE_TOTAL = t(K.TITLE_FUEL_TRIM_TOTAL)
    private val TITLE_ASYM = t(K.TITLE_BANK_ASYMMETRY)
    override val ids = listOf(LONG, TOTAL, ASYM)

    /** ltft/stft come from the latest recording with warm LTFT for the bank (evidence); [recentLtft] is what is judged. */
    private data class Bank(
        val n: Int, val stft: Agg, val ltft: Agg, val ltftAll: Agg, val atMs: Long, val sessions: List<Long>,
        val recentLtft: Double, val recentTotal: Double?, val key: NormKey,
    )

    override fun evaluate(input: AnalysisInput): List<Finding> {
        if (input.traits.isDiesel) {
            val msg = t(K.FUEL_TRIM_NOT_APPLICABLE)
            return listOf(noData(LONG, TITLE_LONG, msg), noData(TOTAL, TITLE_TOTAL, msg), noData(ASYM, TITLE_ASYM, msg))
        }
        fun bank(n: Int, stftId: String, ltftId: String, key: NormKey): Bank? {
            val latest = input.sessions.filter { (it.warmTrims[ltftId]?.count ?: 0) > 0 }.maxByOrNull { it.startedAtMs } ?: return null
            val stft = latest.warmTrims[stftId]?.takeIf { it.count > 0 } ?: Agg.EMPTY
            // LTFT is itself the ECU's long-term adaptation: the latest recording describes it (older months don't).
            val lt = recent(input.sessions, limit = 1) { d -> d.warmTrims[ltftId]?.takeIf { it.count > 0 }?.mean }!!
            val total = recent(input.sessions, limit = 1) { d -> d.total(stftId, ltftId) }
            return Bank(
                n, stft, latest.warmTrims.getValue(ltftId), input.sessions.mapNotNull { it.warmTrims[ltftId] }.merged(), latest.startedAtMs,
                lt.second.map { it.sessionId }, lt.first, total?.first, key,
            )
        }
        val withLt = listOfNotNull(bank(1, SensorIds.STFT1, SensorIds.LTFT1, NormKey.LTFT_B1), bank(2, SensorIds.STFT2, SensorIds.LTFT2, NormKey.LTFT_B2))
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
        } + ev(K.EVIDENCE_RECENT, t(K.VALUE_PLAIN, FindingArg.Join(withLt.map { t(K.BANK_PERCENT, cnt(it.n), signed(it.recentLtft)) })))
        val sessions = withLt.flatMap { it.sessions }.distinct()
        val fewNote = if (withLt.any(few)) t(K.FEW_SAMPLES_NOTE) else null
        val ltNorm = withLt.associateWith { input.norms.effective(it.key) }

        val out = withLt.filter { ltNorm[it]?.outside(it.recentLtft) == true }
        val long = if (out.isEmpty()) {
            ok(LONG, TITLE_LONG, t(K.LTFT_OK_NORM), evidence).copy(norms = ltNorm.values.filterNotNull())
        } else {
            val parts = out.map { t(K.BANK_PERCENT, cnt(it.n), signed(it.recentLtft)) }
            val lean = out.all { it.recentLtft > 0 }
            Finding(
                LONG, Severity.WARNING, TITLE_LONG,
                listOfNotNull(t(K.LTFT_OUT_NORM, FindingArg.Join(parts)), if (lean) t(K.LEAN_NOTE) else null, fewNote),
                evidence, sessions, norms = out.mapNotNull { ltNorm[it] },
            )
        }

        val totalNorm = input.norms.effective(NormKey.TOTAL_TRIM)
        val totals = withLt.filter { it.stft.count > 0 && it.recentTotal != null }.map { it to it.recentTotal!! }
        val total = when {
            totals.isEmpty() || totalNorm == null -> noData(TOTAL, TITLE_TOTAL, t(K.TOTAL_TRIM_NO_DATA))
            totals.none { totalNorm.outside(it.second) } ->
                ok(TOTAL, TITLE_TOTAL, t(K.TOTAL_TRIM_OK_NORM), evidence).copy(norms = listOf(totalNorm))
            else -> {
                val bad = totals.filter { totalNorm.outside(it.second) }
                val enough = bad.all { (b, _) -> b.ltft.count >= Thresholds.MIN_TRIM_SAMPLES && b.stft.count >= Thresholds.MIN_TRIM_SAMPLES }
                Finding(
                    TOTAL, if (enough) Severity.CRITICAL else Severity.WARNING, TITLE_TOTAL,
                    listOfNotNull(
                        t(K.TOTAL_TRIM_OUT_NORM, FindingArg.Join(bad.map { (b, v) -> t(K.BANK_PERCENT, cnt(b.n), signed(v)) })),
                        if (enough) null else t(K.PRELIMINARY_NOTE),
                    ),
                    evidence, sessions, norms = listOf(totalNorm),
                )
            }
        }

        val asymNorm = input.norms.effective(NormKey.BANK_ASYMMETRY)
        val asym = if (withLt.size < 2 || asymNorm == null) {
            noData(ASYM, TITLE_ASYM, t(K.BANK_ASYMMETRY_NO_DATA))
        } else {
            val diff = abs(withLt[0].recentLtft - withLt[1].recentLtft)
            val ev = evidence + ev(K.EVIDENCE_LTFT_DIFFERENCE, t(K.VALUE_PERCENT, num(diff)))
            if (asymNorm.above(diff)) {
                Finding(ASYM, Severity.WARNING, TITLE_ASYM, listOfNotNull(t(K.BANK_ASYMMETRY, num(diff)), fewNote), ev, sessions, norms = listOf(asymNorm))
            } else {
                ok(ASYM, TITLE_ASYM, t(K.BANK_ASYMMETRY_OK, num(diff)), ev).copy(norms = listOf(asymNorm))
            }
        }
        return listOf(long, total, asym)
    }
}

/**
 * Warm idle air flow against [NormKey.IDLE_MAF]: the own baseline (absolute g/s, works without the displacement) or
 * the generic 0.9–1.3 g/s per litre of a petrol engine. No MAF sensor (ECU) or a diesel without a baseline: no data.
 */
object MafIdleRule : AnalyticsRule {
    private const val ID = "maf-idle"
    private val TITLE = t(K.TITLE_MAF_IDLE)
    override val ids = listOf(ID)

    override fun evaluate(input: AnalysisInput): List<Finding> {
        if (input.traits.hasMaf?.value == false) return listOf(noData(ID, TITLE, t(K.MAF_NOT_SUPPORTED)))
        val qualifying = input.sessions.filter { it.warmIdleMaf.count >= Thresholds.MAF_IDLE_MIN_SAMPLES }
        val volume = input.car?.engineVolumeL?.takeIf { it > 0 }
        val maf = input.sessions.map { it.warmIdleMaf }.merged()
        val norm = input.norms.effective(NormKey.IDLE_MAF)
        if (norm == null) {
            if (volume == null && !input.traits.isDiesel) {
                val measured = maf.mean?.let { t(K.MAF_MEASURED, num(it)) }
                return listOf(Finding(ID, Severity.NO_DATA, TITLE, listOfNotNull(t(K.MAF_NO_VOLUME), measured)))
            }
            return listOf(noData(ID, TITLE, t(K.MAF_LEARNING, cnt(BaselineLearner.MIN_SESSIONS))))
        }
        val r = recent(qualifying) { it.warmIdleMaf.mean } ?: return listOf(noData(ID, TITLE, t(K.MAF_NO_SAMPLES)))
        val value = r.first
        val evidence = listOfNotNull(
            ev(K.EVIDENCE_MAF_WARM_IDLE, t(K.MAF_VALUE, num(value), cnt(r.second.sumOf { it.warmIdleMaf.count }))),
            volume?.let { ev(K.EVIDENCE_ENGINE_VOLUME, t(K.VALUE_LITERS, num(it))) },
            volume?.let { ev(K.EVIDENCE_PER_LITER, t(K.PER_LITER_PLAIN, num(value / it, 2))) },
        )
        val sessions = r.second.map { it.sessionId }
        return listOf(
            when {
                norm.below(value) -> Finding(ID, Severity.WARNING, TITLE, t(K.MAF_LOW_NORM, num(value)), evidence, sessions, norms = listOf(norm))
                norm.above(value) -> Finding(ID, Severity.INFO, TITLE, t(K.MAF_HIGH_NORM, num(value)), evidence, sessions, norms = listOf(norm))
                else -> ok(ID, TITLE, t(K.MAF_OK_NORM, num(value)), evidence).copy(norms = listOf(norm))
            },
        )
    }
}

/** R-H: warm idle speed (recent sessions' medians) against [NormKey.IDLE_RPM]; low = stall risk (WARNING), high = INFO. */
object IdleRpmRule : AnalyticsRule {
    private const val ID = "idle-rpm"
    private val TITLE = t(K.TITLE_IDLE_RPM)
    override val ids = listOf(ID)

    override fun evaluate(input: AnalysisInput): List<Finding> {
        val r = recent(input.sessions) { BaselineLearner.sessionValue(NormKey.IDLE_RPM, it) }
            ?: return listOf(noData(ID, TITLE, t(K.IDLE_RPM_NO_DATA)))
        val norm = input.norms.effective(NormKey.IDLE_RPM) ?: return listOf(noData(ID, TITLE, t(K.IDLE_RPM_NO_DATA)))
        val v = r.first
        val evidence = listOf(ev(K.EVIDENCE_RECENT, NormText.value(NormUnit.RPM, v)))
        val sessions = r.second.map { it.sessionId }
        return listOf(
            when {
                norm.below(v) -> Finding(ID, Severity.WARNING, TITLE, t(K.IDLE_RPM_LOW, num(v, 0)), evidence, sessions, norms = listOf(norm))
                norm.above(v) -> Finding(ID, Severity.INFO, TITLE, t(K.IDLE_RPM_HIGH, num(v, 0)), evidence, sessions, norms = listOf(norm))
                else -> ok(ID, TITLE, t(K.IDLE_RPM_OK, num(v, 0)), evidence).copy(norms = listOf(norm))
            },
        )
    }
}

/**
 * R-H: coolant plateau (thermostat open) of the recent sessions against [NormKey.COOLANT_PLATEAU]. Below the own norm
 * = thermostat opens early/stays open (WARNING); above = cooling margin shrinking (INFO). The generic band starts at
 * the warm gate, so without a baseline this rule cannot fire low — honest: a generic number can't tell a 92 °C
 * thermostat from an 82 °C one. Overheating itself is the class-2 [CoolantOverheatRule].
 */
object CoolantPlateauRule : AnalyticsRule {
    private const val ID = "coolant-plateau"
    private val TITLE = t(K.TITLE_COOLANT_PLATEAU)
    override val ids = listOf(ID)

    override fun evaluate(input: AnalysisInput): List<Finding> {
        val r = recent(input.sessions) { it.coolantPlateau() } ?: return listOf(noData(ID, TITLE, t(K.COOLANT_PLATEAU_NO_DATA)))
        val norm = input.norms.effective(NormKey.COOLANT_PLATEAU) ?: return listOf(noData(ID, TITLE, t(K.COOLANT_PLATEAU_NO_DATA)))
        val v = r.first
        val evidence = listOf(ev(K.EVIDENCE_RECENT, t(K.VALUE_CELSIUS, num(v, 0))))
        val sessions = r.second.map { it.sessionId }
        return listOf(
            when {
                norm.below(v) -> Finding(ID, Severity.WARNING, TITLE, t(K.COOLANT_PLATEAU_LOW, num(v, 0)), evidence, sessions, norms = listOf(norm))
                norm.above(v) -> Finding(ID, Severity.INFO, TITLE, t(K.COOLANT_PLATEAU_HIGH, num(v, 0)), evidence, sessions, norms = listOf(norm))
                else -> ok(ID, TITLE, t(K.COOLANT_PLATEAU_OK, num(v, 0)), evidence).copy(norms = listOf(norm))
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

/** B10: engine stalls found live (rpm → 0 with the ECU answering). 1 = info, 2+ = warning, 4+ = critical. */
object StallRule : AnalyticsRule {
    private const val ID = "engine-stalls"
    private val TITLE = t(K.TITLE_STALLS)
    override val ids = listOf(ID)

    override fun evaluate(input: AnalysisInput): List<Finding> {
        val stalls = input.events.filter { it.type == TripEventType.STALL }.sortedBy { it.atMs }
        if (stalls.isEmpty()) {
            return listOf(if (input.sessions.isEmpty()) noData(ID, TITLE, t(K.STALLS_NO_DATA)) else ok(ID, TITLE, t(K.STALLS_OK)))
        }
        val sessions = stalls.map { it.sessionId }.distinct()
        val severity = when {
            stalls.size >= Thresholds.STALL_FINDING_CRITICAL -> Severity.CRITICAL
            stalls.size >= Thresholds.STALL_FINDING_MIN -> Severity.WARNING
            else -> Severity.INFO
        }
        return listOf(
            Finding(
                ID, severity, TITLE,
                listOfNotNull(t(K.STALLS_SEEN, cnt(stalls.size), cnt(sessions.size)), t(K.STALLS_HINT).takeIf { severity != Severity.INFO }),
                listOf(
                    ev(K.EVIDENCE_STALLS, countValue(stalls.size)),
                    ev(K.EVIDENCE_LAST_STALL, t(K.RAW, FindingArg.Date(stalls.last().atMs))),
                ),
                sessions, stalls.first().atMs, stalls.last().atMs,
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
        val norms = listOfNotNull(input.norms.effective(NormKey.WARMUP_MIN))
        return listOf(
            if (late > early * Thresholds.WARMUP_TREND_RATIO) {
                Finding(
                    ID, Severity.INFO, TITLE,
                    t(K.WARMUP_LONGER, minutes(early), minutes(late)),
                    evidence, starts.drop(starts.size - half).map { it.sessionId }, norms = norms,
                )
            } else {
                ok(ID, TITLE, t(K.WARMUP_OK), evidence).copy(norms = norms)
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

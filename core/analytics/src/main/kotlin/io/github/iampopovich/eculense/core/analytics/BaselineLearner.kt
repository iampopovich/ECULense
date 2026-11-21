package io.github.iampopovich.eculense.core.analytics

import kotlin.math.abs
import kotlin.math.max

/**
 * R-H own baseline: robust median and MAD (Leys et al. 2013, J. Exp. Soc. Psychol. 49(4):764–766,
 * https://doi.org/10.1016/j.jesp.2013.03.013) of per-session values from the car's own warm sessions.
 *
 * Band = median ± max([K]·MAD, [NormKey.minDelta]), MAD scaled by b = 1.4826 (consistent with σ under normality,
 * Huber 1981 via Leys). [K] = 2.5 is Leys' recommended «moderately conservative» default; the absolute floor keeps a
 * very steady car (MAD ≈ 0) from flagging sensor-resolution noise.
 *
 * Learning rules (a fault must never become the norm):
 * - at least [MIN_SESSIONS] qualifying sessions before a baseline is used: with n = 5 the median and MAD tolerate up to
 *   two contaminated sessions (breakdown point 50 %); below that a single odd trip would move the band.
 * - rolling window of the last [WINDOW_SESSIONS] qualifying sessions: a count window adapts to how often the car is
 *   driven (a daily car refreshes in weeks, a weekend car in months) and to the season (charging voltage and warm-up
 *   depend on temperature); n = 30 puts the standard error of the median near 0.23 σ (1.25 σ/√n).
 * - sessions listed by a verified (non-generic) finding of the same norm are left out, and while such a finding is
 *   active the previous baseline is kept frozen;
 * - sessions within [DTC_EXCLUDE_MS] of a stored/pending code of the norm's system are left out;
 * - values beyond the class-2 safety limits are never learned (a dead alternator is not a «norm»).
 * Findings based only on the GENERIC norm do not block learning: otherwise a generic default that does not fit the
 * engine (12.6 V of a smart alternator) would decide forever.
 */
object BaselineLearner {
    const val K = 2.5
    const val MAD_B = 1.4826
    const val MIN_SESSIONS = 5
    const val WINDOW_SESSIONS = 30
    /** Recent value for a finding = median of the last sessions (one odd trip is not a trend). */
    const val RECENT_SESSIONS = 3
    /** A code read marks its fault as present around that date: ±7 days cover the drive cycles that set and age it. */
    const val DTC_EXCLUDE_MS = 7L * 86_400_000
    /** Session value gates (D): enough samples for a per-session median. */
    const val MIN_SESSION_SAMPLES = 10
    const val MIN_PLATEAU_SAMPLES = 30

    fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Scaled MAD (b = 1.4826). */
    fun mad(v: List<Double>): Double {
        val m = median(v)
        return MAD_B * median(v.map { abs(it - m) })
    }

    /** One per-session value of [key], or null when the session does not qualify. */
    fun sessionValue(key: NormKey, d: SessionDigest): Double? = when (key) {
        NormKey.COOLANT_PLATEAU -> d.coolantPlateau()
        NormKey.IDLE_RPM -> d.warmIdleRpmHist.takeIf { it.count >= MIN_SESSION_SAMPLES }?.quantile(0.5)
        NormKey.IDLE_MAF -> d.warmIdleMaf.takeIf { it.count >= Thresholds.MAF_IDLE_MIN_SAMPLES }?.mean
        NormKey.CHARGING_IDLE_V -> d.idleVoltageHist.takeIf { it.count >= MIN_SESSION_SAMPLES }?.quantile(0.5)
        NormKey.CHARGING_DRIVING_V -> d.drivingVoltageHist.takeIf { it.count >= MIN_SESSION_SAMPLES }?.quantile(0.5)
        NormKey.LTFT_B1 -> d.trimMean(SensorIds.LTFT1)
        NormKey.LTFT_B2 -> d.trimMean(SensorIds.LTFT2)
        NormKey.TOTAL_TRIM -> listOfNotNull(d.total(SensorIds.STFT1, SensorIds.LTFT1), d.total(SensorIds.STFT2, SensorIds.LTFT2)).maxByOrNull { abs(it) }
        NormKey.BANK_ASYMMETRY -> {
            val a = d.trimMean(SensorIds.LTFT1)
            val b = d.trimMean(SensorIds.LTFT2)
            if (a != null && b != null) abs(a - b) else null
        }
        NormKey.WARMUP_MIN -> d.warmupMs?.let { it / 60_000.0 }
        NormKey.IDLE_RPM_SWING, NormKey.O2_SWING, NormKey.HARSH_ACCEL -> null
    }

    /** Class-2 gate: such values are faults by any standard and never enter a baseline. */
    private fun learnable(key: NormKey, v: Double): Boolean = when (key.system) {
        NormSystem.CHARGING -> v >= Thresholds.LIVE_NOT_CHARGING_V && v <= Thresholds.LIVE_OVERCHARGE_V
        NormSystem.COOLING -> key != NormKey.COOLANT_PLATEAU || v < Thresholds.OVERHEAT_WARN_C
        else -> true
    }

    /** The newest sessions with a value of [key] (newest first), at most [limit]. */
    fun recent(key: NormKey, sessions: List<SessionDigest>, limit: Int = RECENT_SESSIONS): List<Pair<SessionDigest, Double>> =
        sessions.sortedByDescending { it.startedAtMs }.mapNotNull { d -> sessionValue(key, d)?.let { d to it } }.take(limit)

    /** Median of the [recent] values, with the sessions it came from; null without any. */
    fun recentValue(key: NormKey, sessions: List<SessionDigest>): Pair<Double, List<SessionDigest>>? {
        val r = recent(key, sessions)
        if (r.isEmpty()) return null
        return median(r.map { it.second }) to r.map { it.first }
    }

    fun band(key: NormKey, median: Double, mad: Double): Pair<Double?, Double?> {
        val floor = if (key.relative) key.minDelta * abs(median) else key.minDelta
        val half = max(K * mad, floor)
        return when (key) {
            NormKey.BANK_ASYMMETRY -> null to median + half
            else -> median - half to median + half
        }
    }

    /** Codes of a norm's system (SAE J2012 generic P0 ranges). */
    fun dtcOfSystem(system: NormSystem, code: String): Boolean {
        if (code.length != 5 || !code.startsWith("P0")) return false
        val n = code.drop(2).toIntOrNull() ?: return false
        return when (system) {
            NormSystem.COOLING -> n in 115..119 || n == 125 || n == 128 || n == 217
            NormSystem.IDLE -> n in 505..507 || n in 300..312
            NormSystem.AIR -> n in 100..109 || n == 171 || n == 174
            NormSystem.CHARGING -> n in 560..563 || n in 620..625
            NormSystem.FUEL_TRIM -> n in 170..175 || n in 130..167 || n in 300..312
            NormSystem.O2 -> n in 130..167
            NormSystem.DRIVING -> false
        }
    }

    /**
     * Learns one BASELINE norm per learnable key from [sessions]. [findings] = the evaluation with the current norms
     * (verified findings freeze and exclude), [previous] = stored norms (frozen baselines are returned unchanged).
     * Keys without any qualifying session are not returned.
     */
    fun learn(sessions: List<SessionDigest>, findings: List<Finding>, dtcEvents: List<DtcEvent>, previous: CarNorms, nowMs: Long): List<Norm> =
        NormKey.entries.filter { it.learnable }.mapNotNull { key ->
            val verified = findings.filter { f ->
                f.severity.rank >= Severity.WARNING.rank && f.norms.any { it.key == key && !it.isGeneric }
            }
            val old = previous.baseline(key)
            if (verified.isNotEmpty() && old != null) return@mapNotNull old
            val excludedSessions = verified.flatMap { it.sessionIds }.toSet()
            val codes = dtcEvents.filter { dtcOfSystem(key.system, it.code) }.map { it.atMs }
            val values = sessions.asSequence()
                .filter { it.sessionId !in excludedSessions }
                .filter { s -> codes.none { abs(it - s.startedAtMs) <= DTC_EXCLUDE_MS } }
                .mapNotNull { s -> sessionValue(key, s)?.takeIf { learnable(key, it) }?.let { s.startedAtMs to it } }
                .sortedByDescending { it.first }
                .take(WINDOW_SESSIONS)
                .map { it.second }
                .toList()
            if (values.isEmpty()) return@mapNotNull null
            val m = median(values)
            val d = mad(values)
            val (lo, hi) = band(key, m, d)
            Norm(
                key, lo, hi, NormSource.BASELINE, center = m, mad = d, sampleCount = values.size,
                confidence = (values.size.toDouble() / WINDOW_SESSIONS).coerceAtMost(1.0), updatedAtMs = nowMs,
            )
        }
}

/** Median of the warm coolant samples (thermostat open), from the 0.1 °C histogram; null below [BaselineLearner.MIN_PLATEAU_SAMPLES]. */
fun SessionDigest.coolantPlateau(): Double? {
    val warmBin = (Thresholds.WARM_COOLANT_C * 10).toInt()
    val warm = Histogram(coolantHist.bins.filterKeys { it >= warmBin })
    return warm.takeIf { it.count >= BaselineLearner.MIN_PLATEAU_SAMPLES }?.quantile(0.5)
}

internal fun SessionDigest.trimMean(id: String): Double? = warmTrims[id]?.takeIf { it.count >= Thresholds.MIN_TRIM_SAMPLES }?.mean

internal fun SessionDigest.total(stft: String, ltft: String): Double? {
    val s = warmTrims[stft]?.takeIf { it.count > 0 }?.mean ?: return null
    val l = warmTrims[ltft]?.takeIf { it.count > 0 }?.mean ?: return null
    return s + l
}

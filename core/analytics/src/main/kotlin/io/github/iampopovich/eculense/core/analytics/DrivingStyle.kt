package io.github.iampopovich.eculense.core.analytics

import kotlin.math.min
import kotlin.math.roundToInt

/** D6 thresholds as data (no GPS: speed comes from PID 01.0D only). Sources: telematics «harsh event» practice ≈ 0.3 g. */
object StyleThresholds {
    /** Harsh acceleration, m/s² (≈ 0.3 g) at ≤ 1 s between samples. */
    const val HARSH_ACCEL_MS2 = 3.0
    /** Harsh braking, m/s² (≈ 0.35 g). */
    const val HARSH_BRAKE_MS2 = 3.5
    /**
     * A derivative over a longer interval averages the peak away: the threshold is scaled down for slower links
     * (K-Line ≈ 1–2 s per speed sample). Pairs = (max interval ms, factor); intervals above the last are not judged.
     */
    val INTERVAL_FACTORS: List<Pair<Long, Double>> = listOf(1_000L to 1.0, 2_000L to 0.85, 3_000L to 0.75)
    /** Scoring is skipped when the median speed interval is longer than this: events can't be seen reliably. */
    const val MAX_MEDIAN_INTERVAL_MS = 2_000L
    const val HIGH_RPM = 3_500.0
    const val MIN_DISTANCE_KM = 2.0
    /** Idle share above this starts to cost points. */
    const val IDLE_FREE_SHARE = 0.25
    const val MOVING_KMH = 2.0
    /** Longer gaps (link loss) are neither driving nor standing. */
    const val MAX_GAP_MS = 10_000L

    // Penalty weights and caps (points of 100).
    const val ACCEL_POINTS_PER_EVENT_PER_10KM = 6.0
    const val BRAKE_POINTS_PER_EVENT_PER_10KM = 8.0
    const val EVENT_CAP = 30.0
    const val HIGH_RPM_POINTS_PER_PERCENT = 1.0
    const val HIGH_RPM_CAP = 20.0
    const val IDLE_POINTS_PER_SHARE = 50.0
    const val IDLE_CAP = 15.0
}

enum class StylePenalty { HARSH_ACCEL, HARSH_BRAKE, HIGH_RPM, IDLE }

enum class StyleSkipReason {
    /** No speed samples in the recording. */
    NO_SPEED,
    /** Speed came slower than one sample per 2 s (typical for K-Line with many PIDs): events would be missed. */
    SPARSE_SAMPLES,
    /** Under [StyleThresholds.MIN_DISTANCE_KM]. */
    TOO_SHORT,
}

sealed interface DrivingStyle {
    data class Scored(
        val score: Int,
        val distanceKm: Double,
        val accelEvents: Int,
        val brakeEvents: Int,
        /** Share of rpm samples above [StyleThresholds.HIGH_RPM], 0..1; null without rpm. */
        val highRpmShare: Double?,
        /** Standing time / (driving + standing), 0..1. */
        val idleShare: Double,
        val medianIntervalMs: Long,
        val penalties: Map<StylePenalty, Double>,
    ) : DrivingStyle {
        /** The penalty that cost the most points: the tip shown first; null when nothing was deducted. */
        val topPenalty: StylePenalty? get() = penalties.filterValues { it > 0.0 }.maxByOrNull { it.value }?.key
    }

    data class NotScored(val reason: StyleSkipReason, val medianIntervalMs: Long? = null) : DrivingStyle
}

/**
 * D6: a 0–100 score per trip from harsh accelerations/brakings (speed derivative), the share of high rpm and the idle
 * share. Single pass over (sensorId, value, timestamp) in time order.
 */
object DrivingStyleRules {
    fun evaluate(points: Iterable<Triple<String, Double, Long>>): DrivingStyle {
        val t = StyleThresholds
        val intervals = mutableListOf<Long>()
        var lastV: Double? = null
        var lastT = 0L
        var distanceKm = 0.0
        var drivingMs = 0L
        var standingMs = 0L
        var accel = 0
        var brake = 0
        var inAccel = false
        var inBrake = false
        var rpmSamples = 0
        var rpmHigh = 0
        for ((id, v, ts) in points) {
            when (id) {
                SensorIds.RPM -> {
                    rpmSamples++
                    if (v > t.HIGH_RPM) rpmHigh++
                }
                SensorIds.SPEED -> {
                    val pv = lastV
                    if (pv != null) {
                        val dt = ts - lastT
                        if (dt in 1..t.MAX_GAP_MS) {
                            intervals += dt
                            val avg = (pv + v) / 2
                            distanceKm += avg * dt / 3_600_000.0
                            if (avg > t.MOVING_KMH) drivingMs += dt else standingMs += dt
                            val factor = t.INTERVAL_FACTORS.firstOrNull { dt <= it.first }?.second
                            if (factor != null) {
                                val a = (v - pv) / 3.6 / (dt / 1000.0)
                                val isAccel = a > t.HARSH_ACCEL_MS2 * factor
                                val isBrake = -a > t.HARSH_BRAKE_MS2 * factor
                                if (isAccel && !inAccel) accel++
                                if (isBrake && !inBrake) brake++
                                inAccel = isAccel
                                inBrake = isBrake
                            } else {
                                inAccel = false
                                inBrake = false
                            }
                        } else {
                            inAccel = false
                            inBrake = false
                        }
                    }
                    lastV = v
                    lastT = ts
                }
            }
        }
        if (intervals.isEmpty()) return DrivingStyle.NotScored(StyleSkipReason.NO_SPEED)
        val median = intervals.sorted()[intervals.size / 2]
        if (median > t.MAX_MEDIAN_INTERVAL_MS) return DrivingStyle.NotScored(StyleSkipReason.SPARSE_SAMPLES, median)
        if (distanceKm < t.MIN_DISTANCE_KM) return DrivingStyle.NotScored(StyleSkipReason.TOO_SHORT, median)
        val per10 = 10.0 / distanceKm
        val highShare = if (rpmSamples > 0) rpmHigh.toDouble() / rpmSamples else null
        val total = drivingMs + standingMs
        val idle = if (total > 0) standingMs.toDouble() / total else 0.0
        val penalties = mapOf(
            StylePenalty.HARSH_ACCEL to min(t.EVENT_CAP, accel * per10 * t.ACCEL_POINTS_PER_EVENT_PER_10KM),
            StylePenalty.HARSH_BRAKE to min(t.EVENT_CAP, brake * per10 * t.BRAKE_POINTS_PER_EVENT_PER_10KM),
            StylePenalty.HIGH_RPM to min(t.HIGH_RPM_CAP, (highShare ?: 0.0) * 100 * t.HIGH_RPM_POINTS_PER_PERCENT),
            StylePenalty.IDLE to min(t.IDLE_CAP, maxOf(0.0, idle - t.IDLE_FREE_SHARE) * t.IDLE_POINTS_PER_SHARE),
        )
        val score = (100 - penalties.values.sum()).roundToInt().coerceIn(0, 100)
        return DrivingStyle.Scored(score, distanceKm, accel, brake, highShare, idle, median, penalties)
    }

    /** Plain English for the AI. */
    fun english(s: DrivingStyle): String = when (s) {
        is DrivingStyle.NotScored -> when (s.reason) {
            StyleSkipReason.NO_SPEED -> "not scored: no speed samples"
            StyleSkipReason.SPARSE_SAMPLES -> "not scored: speed sampled every ${s.medianIntervalMs} ms (slower than 1 per 2 s), harsh events can't be seen"
            StyleSkipReason.TOO_SHORT -> "not scored: trip shorter than ${StyleThresholds.MIN_DISTANCE_KM} km"
        }
        is DrivingStyle.Scored -> "score ${s.score}/100; harsh accelerations ${s.accelEvents}, harsh brakings ${s.brakeEvents}" +
            (s.highRpmShare?.let { ", above ${StyleThresholds.HIGH_RPM.toInt()} rpm ${(it * 100).roundToInt()} % of samples" } ?: "") +
            ", idle ${(s.idleShare * 100).roundToInt()} % of the time" + (s.topPenalty?.let { "; biggest deduction: ${it.name.lowercase()}" } ?: "")
    }
}

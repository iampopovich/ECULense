package io.github.iampopovich.eculense.core.analytics

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * D6 thresholds as data (no GPS: speed comes from PID 01.0D only).
 *
 * Harsh level = Geotab's GPS-based harsh-event rules for passenger vehicles, 0.300 g for both acceleration and braking
 * (g = 9.80665 m/s², so 2.94 m/s²). Those rules work on the speed change between points, not on an accelerometer, which
 * is the closest analogue to a speed PID. Source: https://support.geotab.com/help/mygeotab/groups-and-rules/rules/rules-overview,
 * section «G-force thresholds for GPS-based rules».
 *
 * Severe level (reference only, for a later UI) = Geotab's accelerometer defaults for passenger cars: acceleration
 * > 0.43 g, braking < −0.61 g. Source: https://support.geotab.com/help/mygeotab/reports/safety-reports/aggressive-driving-report.
 *
 * Literature range: US DOT reports use 0.25–0.78 g for passenger vehicles (summary in MDPI Vehicles 8(3):68,
 * https://mdpi.com/2624-8921/8/3/68); Ehsani et al. 2017 put near-miss levels at 0.6 g braking and 0.5 g acceleration.
 *
 * Window: acceleration is the speed change between the current sample and the latest sample at least
 * [MIN_WINDOW_MS] earlier, and the window is capped at [MAX_WINDOW_MS] (a longer one is not judged). PID 01.0D has a
 * 1 km/h resolution, so one step over 0.5 s already reads as 0.56 m/s² of noise; a harsh event at 0.3 g lasts several
 * seconds (60→0 km/h at 0.3 g ≈ 5.7 s), so a 1–3 s window still sees it. No per-interval correction factors.
 *
 * How to calibrate on a real car (field test 1.23; the phone-accelerometer logging of E5-lite does (a), see
 * [AccelCalibrationRules], and its result can be stored as a per-car override passed to [DrivingStyleRules.evaluate]):
 * - Mount the phone rigidly and log Android TYPE_LINEAR_ACCELERATION at ≥ 50 Hz together with the OBD 01.0D stream
 *   (same clock, from the recorder). Compare the accelerometer peak over each event with the windowed speed derivative
 *   computed here at the real K-Line sample rate, fit the ratio and adjust the threshold or the window from that.
 * - Without an extra sensor: a per-driver, data-driven threshold of μ + 2σ of the longitudinal acceleration
 *   distribution over that driver's trips (the SD method of naturalistic-driving studies), shown next to the fixed 0.3 g.
 */
object StyleThresholds {
    const val G = 9.80665
    /** Harsh acceleration, m/s² (0.300 g, Geotab GPS-based rule). */
    const val HARSH_ACCEL_MS2 = 0.300 * G
    /** Harsh braking, m/s² (0.300 g, Geotab GPS-based rule). */
    const val HARSH_BRAKE_MS2 = 0.300 * G
    /** Severe acceleration, m/s² (Geotab accelerometer default for passenger cars, 0.43 g). Not scored yet. */
    const val SEVERE_ACCEL_MS2 = 0.43 * G
    /** Severe braking, m/s² (Geotab accelerometer default for passenger cars, 0.61 g). Not scored yet. */
    const val SEVERE_BRAKE_MS2 = 0.61 * G
    /** Shortest speed window for the derivative: below it the 1 km/h resolution of 01.0D dominates. */
    const val MIN_WINDOW_MS = 1_000L
    /** Longest window that is still judged: longer ones average a harsh event away. */
    const val MAX_WINDOW_MS = 3_000L
    /** Scoring is skipped when the median speed window is longer than this: events can't be seen reliably. */
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
    /**
     * [harshMs2] = harsh level for both acceleration and braking; the default is the sourced Geotab 0.3 g, a per-car
     * override comes from the phone-accelerometer calibration (E5-lite, [AccelCalibrationRules]).
     */
    fun evaluate(points: Iterable<Triple<String, Double, Long>>, harshMs2: Double = StyleThresholds.HARSH_ACCEL_MS2): DrivingStyle {
        val t = StyleThresholds
        val intervals = mutableListOf<Long>()
        val windows = mutableListOf<Long>()
        // Recent speed samples (time, km/h) for the sliding window; cleared at link gaps.
        val recent = ArrayDeque<Pair<Long, Double>>()
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
                        } else {
                            recent.clear()
                            inAccel = false
                            inBrake = false
                        }
                    }
                    // Reference = the latest sample at least MIN_WINDOW_MS back; older samples are dropped.
                    while (recent.size >= 2 && ts - recent[1].first >= t.MIN_WINDOW_MS) recent.removeFirst()
                    val ref = recent.firstOrNull()?.takeIf { ts - it.first >= t.MIN_WINDOW_MS }
                    if (ref != null) {
                        val window = ts - ref.first
                        windows += window
                        if (window <= t.MAX_WINDOW_MS) {
                            val a = (v - ref.second) / 3.6 / (window / 1000.0)
                            val isAccel = a > harshMs2
                            val isBrake = -a > harshMs2
                            if (isAccel && !inAccel) accel++
                            if (isBrake && !inBrake) brake++
                            inAccel = isAccel
                            inBrake = isBrake
                        } else {
                            inAccel = false
                            inBrake = false
                        }
                    }
                    recent.addLast(ts to v)
                    lastV = v
                    lastT = ts
                }
            }
        }
        if (intervals.isEmpty()) return DrivingStyle.NotScored(StyleSkipReason.NO_SPEED)
        // The median counts the derivative window, not the raw sample interval.
        val basis = windows.ifEmpty { intervals }
        val median = basis.sorted()[basis.size / 2]
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

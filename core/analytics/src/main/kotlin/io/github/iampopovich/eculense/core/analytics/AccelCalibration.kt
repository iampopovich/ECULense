package io.github.iampopovich.eculense.core.analytics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One device-frame vector sample (m/s²) at a wall-clock time — linear acceleration or gravity. */
data class Vec3Sample(val tMs: Long, val x: Double, val y: Double, val z: Double)

/** One OBD 01.0D speed sample (km/h) at the recorder's wall-clock time. */
data class SpeedSample(val tMs: Long, val kmh: Double)

/** A speed-derivative window exactly as [DrivingStyleRules] judges it: between two real sample times. */
data class SpeedWindow(val startMs: Long, val endMs: Long, val startKmh: Double, val endKmh: Double) {
    val durationMs: Long get() = endMs - startMs
    val accelMs2: Double get() = (endKmh - startKmh) / 3.6 / (durationMs / 1000.0)
    val moving: Boolean get() = max(startKmh, endKmh) > StyleThresholds.MOVING_KMH
}

/** The windowed speed derivative of D6, as a list (calibration and the per-driver threshold need the values). */
object SpeedWindows {
    /**
     * Same rule as [DrivingStyleRules.evaluate]: the reference is the latest sample at least
     * [StyleThresholds.MIN_WINDOW_MS] back; windows longer than [StyleThresholds.MAX_WINDOW_MS] are not judged; a gap
     * over [StyleThresholds.MAX_GAP_MS] (or a repeated timestamp) restarts the window. Input in time order.
     */
    fun derive(speed: List<SpeedSample>): List<SpeedWindow> {
        val t = StyleThresholds
        val out = mutableListOf<SpeedWindow>()
        val recent = ArrayDeque<SpeedSample>()
        var last: SpeedSample? = null
        for (s in speed) {
            val p = last
            if (p != null && (s.tMs - p.tMs) !in 1..t.MAX_GAP_MS) recent.clear()
            while (recent.size >= 2 && s.tMs - recent[1].tMs >= t.MIN_WINDOW_MS) recent.removeFirst()
            val ref = recent.firstOrNull()?.takeIf { s.tMs - it.tMs >= t.MIN_WINDOW_MS }
            if (ref != null && s.tMs - ref.tMs <= t.MAX_WINDOW_MS) out += SpeedWindow(ref.tMs, s.tMs, ref.kmh, s.kmh)
            recent.addLast(s)
            last = s
        }
        return out
    }
}

/**
 * E5-lite calibration constants (plan section 12). The harsh level itself is not redefined here: events use
 * [StyleThresholds.HARSH_ACCEL_MS2] / [StyleThresholds.HARSH_BRAKE_MS2] (0.300 g, Geotab), so the sensor and the OBD
 * score talk about the same event.
 *
 * Filter (road bumps): horizontal acceleration is averaged into [BIN_MS] bins (10 Hz), then a running median over
 * [MEDIAN_BINS] bins removes impulsive spikes of up to 2 bins (≤ 200 ms: pothole, kerb, door) without smearing steps
 * (Tukey's running median; Smith, «The Scientist and Engineer's Guide to DSP», ch. 15–16), then a centred moving
 * average over [SMOOTH_BINS] bins (0.5 s). A moving average of N points at fs has its −3 dB cut-off at ≈ 0.443·fs/N
 * (Smith ch. 15) = 0.89 Hz here, first null at 2 Hz: driver-made longitudinal accelerations last seconds, while body
 * bounce (1–1.5 Hz) and wheel hop (10–15 Hz) are the ride modes of a passenger car (Gillespie, «Fundamentals of
 * Vehicle Dynamics», SAE 1992, ch. 5) and are mostly vertical anyway, i.e. removed by the gravity projection.
 *
 * How to calibrate on a real car: rigid phone holder, setting on, ≥ 20 min of normal driving plus a few firm
 * accelerations and brakings where it is safe; then read the card on the trip page. If [MIN_AXIS_CORRELATION] is
 * not reached on a rigid mount, check that the speed PID was polled at least every 2 s.
 */
object AccelCalibrationThresholds {
    const val BIN_MS = 100L
    const val MEDIAN_BINS = 5
    const val SMOOTH_BINS = 5

    /**
     * Minimum event length on the filtered sensor signal, ms (plan section 12: duration ≥ 0.5 s). A real 0.3 g
     * braking from 50 km/h lasts ≈ 4.7 s; window-based detectors in the literature reject spikes the same way
     * (Mohan et al., «Nericell», SenSys 2008: braking = mean over a sliding window, not a single sample).
     */
    const val MIN_EVENT_MS = 500L

    /**
     * Fewest events with an OBD window before a ratio is recommended. With 5 events the range from the smallest to the
     * largest ratio is a distribution-free 94 % confidence interval for the median (1 − 2·0.5⁵; order-statistic
     * interval, Conover, «Practical Nonparametric Statistics», 3rd ed., §3.2).
     */
    const val MIN_EVENTS = 5

    /**
     * Lowest Pearson correlation between the projected sensor axis and the OBD derivative that counts as a found axis:
     * 0.70 = lower bound of a «strong» correlation (Schober, Boer, Schwarte, «Correlation Coefficients: Appropriate Use
     * and Interpretation», Anesth. Analg. 126(5):1763–1768, 2018). Lower values mean a loose mount or a wrong clock.
     */
    const val MIN_AXIS_CORRELATION = 0.70

    /**
     * Fewest moving speed windows for the axis fit (and for the per-driver threshold). At n = 30 the Fisher-z 95 %
     * interval around r = 0.7 is ≈ 0.45–0.85 (Fisher 1921), i.e. clearly non-random; the usual n ≥ 30 rule of thumb
     * for a stable mean and SD (Hogg & Tanis, «Probability and Statistical Inference», CLT chapter).
     */
    const val MIN_AXIS_WINDOWS = 30

    /** A window's sensor mean needs at least this share of its 100 ms bins filled (sensor gaps, e.g. a suspended CPU). */
    const val MIN_WINDOW_COVERAGE = 0.5

    /**
     * Per-driver threshold = μ + [DRIVER_SIGMAS]·σ of |windowed derivative| while moving: the two-standard-deviation
     * band used for acceleration/deceleration volatility in naturalistic and instrumented-vehicle data (Kamrani,
     * Arvin, Khattak, «How is driving volatility related to intersection safety?», Transp. Res. Part C 100:16–34, 2019).
     */
    const val DRIVER_SIGMAS = 2.0
}

/** Longitudinal axis in the horizontal plane: a_long = cos·h1 + sin·h2 (positive = forward acceleration). */
data class AxisFit(val cos: Double, val sin: Double, val correlation: Double, val windows: Int)

/** One harsh event on the phone sensor and what the OBD windowed derivative saw at the real sample times. */
data class CalibrationEvent(
    val startMs: Long,
    val endMs: Long,
    /** Signed peak of a_long over the event, m/s² (negative = braking). */
    val sensorPeakMs2: Double,
    val sensorMeanMs2: Double,
    /** Strongest same-sign OBD window overlapping the event; null = no speed window there. */
    val obdMs2: Double?,
) {
    val ratio: Double? get() = obdMs2?.let { it / sensorPeakMs2 }
}

enum class CalibrationGap {
    NO_SENSOR_DATA,
    NO_SPEED,
    /** Fewer than [AccelCalibrationThresholds.MIN_AXIS_WINDOWS] moving windows (phone still, car standing). */
    NO_MOVEMENT,
    /** The horizontal signal has no variation to fit an axis to. */
    AXIS_NOT_FOUND,
    LOW_AXIS_CONFIDENCE,
    TOO_FEW_EVENTS,
    /** The median ratio is not positive: OBD and sensor disagree in sign (clock offset, wrong car). */
    INCONSISTENT_RATIO,
}

data class AccelCalibration(
    val axis: AxisFit?,
    val events: List<CalibrationEvent>,
    val medianRatio: Double?,
    val ratioQ1: Double?,
    val ratioQ3: Double?,
    /** Threshold for the OBD windowed derivative, m/s² (0.3 g × median ratio); null with [gap]. */
    val recommendedMs2: Double?,
    val gap: CalibrationGap?,
) {
    val eventsWithObd: Int get() = events.count { it.obdMs2 != null }
}

/** μ + 2σ of the windowed OBD derivative magnitude over a driver's (car's) trips. */
data class DriverThreshold(val windows: Int, val meanMs2: Double, val sdMs2: Double, val thresholdMs2: Double)

object DriverThresholds {
    fun of(trips: List<List<SpeedSample>>): DriverThreshold? {
        val values = trips.flatMap { trip -> SpeedWindows.derive(trip).filter { it.moving }.map { abs(it.accelMs2) } }
        if (values.size < AccelCalibrationThresholds.MIN_AXIS_WINDOWS) return null
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        return DriverThreshold(values.size, mean, sd, mean + AccelCalibrationThresholds.DRIVER_SIGMAS * sd)
    }
}

/** One 10 Hz row of the CSV export for laptop analysis. */
data class CalibrationRow(
    val tMs: Long,
    val aLongMs2: Double?,
    val aLatMs2: Double?,
    val obdKmh: Double?,
    val obdDerivativeMs2: Double?,
    /** Raw horizontal coordinates (gravity-plane basis), for a re-fit on the laptop when no axis was found. */
    val h1: Double?,
    val h2: Double?,
)

/** Filtered horizontal acceleration on a uniform 10 Hz timeline; NaN = no sensor data in that bin. */
class HorizontalTrack(val t0Ms: Long, val h1: DoubleArray, val h2: DoubleArray) {
    val size: Int get() = h1.size
    fun timeOf(i: Int): Long = t0Ms + i * AccelCalibrationThresholds.BIN_MS + AccelCalibrationThresholds.BIN_MS / 2

    /** Mean of (h1, h2) over bins whose centre lies in [fromMs, toMs]; null below the coverage floor. */
    fun mean(fromMs: Long, toMs: Long): Pair<Double, Double>? {
        val bin = AccelCalibrationThresholds.BIN_MS
        val first = ((fromMs - t0Ms - bin / 2 + bin - 1).floorDiv(bin)).toInt().coerceAtLeast(0)
        val last = ((toMs - t0Ms - bin / 2).floorDiv(bin)).toInt().coerceAtMost(size - 1)
        if (last < first) return null
        var n = 0
        var s1 = 0.0
        var s2 = 0.0
        for (i in first..last) {
            if (h1[i].isNaN()) continue
            n++; s1 += h1[i]; s2 += h2[i]
        }
        val total = last - first + 1
        if (n == 0 || n < total * AccelCalibrationThresholds.MIN_WINDOW_COVERAGE) return null
        return s1 / n to s2 / n
    }
}

/**
 * E5-lite: calibrates the D6 harsh-event threshold from a rigidly mounted phone. Deterministic, pure JVM.
 * 1. Gravity projection: each linear-acceleration sample loses its component along the gravity vector (TYPE_GRAVITY,
 *    nearest earlier sample); the rest is expressed in a fixed horizontal basis built from the mean gravity.
 * 2. Filter: see [AccelCalibrationThresholds] (10 Hz bins → running median → 0.5 s moving average).
 * 3. Axis: over every moving OBD window, the sensor mean (h1, h2) over the same interval is regressed on the OBD
 *    derivative; the direction w = Σ⁻¹·cov(h, a) maximises the correlation (least squares), and its sign makes the
 *    correlation positive, so positive a_long = forward acceleration whatever the mount.
 * 4. Events on the sensor: |a_long| over 0.3 g for ≥ 0.5 s; per event the strongest same-sign OBD window that overlaps
 *    it gives the ratio OBD / sensor peak. Recommended OBD threshold = 0.3 g × median ratio.
 */
object AccelCalibrationRules {
    private val T = AccelCalibrationThresholds

    fun calibrate(linear: List<Vec3Sample>, gravity: List<Vec3Sample>, speed: List<SpeedSample>): AccelCalibration {
        fun gap(g: CalibrationGap, axis: AxisFit? = null, events: List<CalibrationEvent> = emptyList()) =
            AccelCalibration(axis, events, null, null, null, null, g)
        val track = horizontal(linear, gravity) ?: return gap(CalibrationGap.NO_SENSOR_DATA)
        if (speed.isEmpty()) return gap(CalibrationGap.NO_SPEED)
        val windows = SpeedWindows.derive(speed.sortedBy { it.tMs })
        val pairs = windowPairs(track, windows)
        if (pairs.size < T.MIN_AXIS_WINDOWS) return gap(CalibrationGap.NO_MOVEMENT)
        val axis = fitAxis(pairs) ?: return gap(CalibrationGap.AXIS_NOT_FOUND)
        val events = events(track, axis, windows)
        if (axis.correlation < T.MIN_AXIS_CORRELATION) return gap(CalibrationGap.LOW_AXIS_CONFIDENCE, axis, events)
        val ratios = events.mapNotNull { it.ratio }.sorted()
        if (ratios.size < T.MIN_EVENTS) return gap(CalibrationGap.TOO_FEW_EVENTS, axis, events)
        val median = quantile(ratios, 0.5)
        if (median <= 0) return gap(CalibrationGap.INCONSISTENT_RATIO, axis, events)
        return AccelCalibration(
            axis, events, median, quantile(ratios, 0.25), quantile(ratios, 0.75),
            StyleThresholds.HARSH_BRAKE_MS2 * median, null,
        )
    }

    /** 10 Hz rows for the CSV export: projected axes (when an axis was found), OBD speed and the latest window. */
    fun rows(linear: List<Vec3Sample>, gravity: List<Vec3Sample>, speed: List<SpeedSample>): List<CalibrationRow> {
        val track = horizontal(linear, gravity) ?: return emptyList()
        val sorted = speed.sortedBy { it.tMs }
        val windows = SpeedWindows.derive(sorted)
        val axis = windowPairs(track, windows).takeIf { it.size >= T.MIN_AXIS_WINDOWS }?.let(::fitAxis)
        var si = -1
        var wi = -1
        return (0 until track.size).map { i ->
            val t = track.timeOf(i)
            while (si + 1 < sorted.size && sorted[si + 1].tMs <= t) si++
            while (wi + 1 < windows.size && windows[wi + 1].endMs <= t) wi++
            val h1 = track.h1[i].takeUnless { it.isNaN() }
            val h2 = track.h2[i].takeUnless { it.isNaN() }
            val w = windows.getOrNull(wi)?.takeIf { t - it.endMs <= StyleThresholds.MAX_WINDOW_MS }
            CalibrationRow(
                tMs = t,
                aLongMs2 = if (axis != null && h1 != null && h2 != null) axis.cos * h1 + axis.sin * h2 else null,
                aLatMs2 = if (axis != null && h1 != null && h2 != null) -axis.sin * h1 + axis.cos * h2 else null,
                obdKmh = sorted.getOrNull(si)?.takeIf { t - it.tMs <= StyleThresholds.MAX_GAP_MS }?.kmh,
                obdDerivativeMs2 = w?.accelMs2,
                h1 = h1, h2 = h2,
            )
        }
    }

    /** Plain English for the AI (numbers only, no raw samples). */
    fun english(c: AccelCalibration): String {
        val g = StyleThresholds.G
        fun gs(v: Double) = "${(v / g * 1000).roundToInt() / 1000.0} g"
        val axis = c.axis?.let { "axis confidence ${(it.correlation * 100).roundToInt() / 100.0}" } ?: "no axis"
        val head = "phone accelerometer calibration: $axis, events ${c.events.size} (${c.eventsWithObd} with OBD speed)"
        return if (c.gap != null) {
            "not enough data (${c.gap.name.lowercase()}); $head"
        } else {
            "$head, median OBD/sensor ratio ${(c.medianRatio!! * 100).roundToInt() / 100.0} (IQR " +
                "${(c.ratioQ1!! * 100).roundToInt() / 100.0}-${(c.ratioQ3!! * 100).roundToInt() / 100.0}), " +
                "recommended OBD threshold ${gs(c.recommendedMs2!!)} instead of ${gs(StyleThresholds.HARSH_BRAKE_MS2)}"
        }
    }

    // --- internals ---

    internal fun horizontal(linear: List<Vec3Sample>, gravity: List<Vec3Sample>): HorizontalTrack? {
        if (linear.isEmpty() || gravity.isEmpty()) return null
        val acc = linear.sortedBy { it.tMs }
        val grav = gravity.sortedBy { it.tMs }
        var ux = 0.0
        var uy = 0.0
        var uz = 0.0
        grav.forEach { s -> norm(s.x, s.y, s.z).takeIf { it > 1e-6 }?.let { n -> ux += s.x / n; uy += s.y / n; uz += s.z / n } }
        val un = norm(ux, uy, uz).takeIf { it > 1e-6 } ?: return null
        ux /= un; uy /= un; uz /= un
        // e1 = the device axis least aligned with gravity, made horizontal; e2 = u × e1.
        val (rx, ry, rz) = when {
            abs(ux) <= abs(uy) && abs(ux) <= abs(uz) -> Triple(1.0, 0.0, 0.0)
            abs(uy) <= abs(uz) -> Triple(0.0, 1.0, 0.0)
            else -> Triple(0.0, 0.0, 1.0)
        }
        val rd = rx * ux + ry * uy + rz * uz
        var e1x = rx - rd * ux
        var e1y = ry - rd * uy
        var e1z = rz - rd * uz
        val e1n = norm(e1x, e1y, e1z)
        e1x /= e1n; e1y /= e1n; e1z /= e1n
        val e2x = uy * e1z - uz * e1y
        val e2y = uz * e1x - ux * e1z
        val e2z = ux * e1y - uy * e1x

        val t0 = acc.first().tMs
        val bins = ((acc.last().tMs - t0) / T.BIN_MS + 1).toInt()
        val s1 = DoubleArray(bins)
        val s2 = DoubleArray(bins)
        val n = IntArray(bins)
        var gi = 0
        for (a in acc) {
            while (gi + 1 < grav.size && grav[gi + 1].tMs <= a.tMs) gi++
            val gs = grav[gi]
            val gn = norm(gs.x, gs.y, gs.z).takeIf { it > 1e-6 } ?: continue
            val gx = gs.x / gn
            val gy = gs.y / gn
            val gz = gs.z / gn
            val d = a.x * gx + a.y * gy + a.z * gz
            val hx = a.x - d * gx
            val hy = a.y - d * gy
            val hz = a.z - d * gz
            val b = ((a.tMs - t0) / T.BIN_MS).toInt()
            s1[b] += hx * e1x + hy * e1y + hz * e1z
            s2[b] += hx * e2x + hy * e2y + hz * e2z
            n[b]++
        }
        val m1 = DoubleArray(bins) { if (n[it] > 0) s1[it] / n[it] else Double.NaN }
        val m2 = DoubleArray(bins) { if (n[it] > 0) s2[it] / n[it] else Double.NaN }
        return HorizontalTrack(t0, smooth(median(m1)), smooth(median(m2)))
    }

    private fun median(x: DoubleArray): DoubleArray {
        val half = T.MEDIAN_BINS / 2
        val buf = DoubleArray(T.MEDIAN_BINS)
        return DoubleArray(x.size) { i ->
            if (x[i].isNaN()) return@DoubleArray Double.NaN
            var k = 0
            for (j in maxOf(0, i - half)..minOf(x.size - 1, i + half)) if (!x[j].isNaN()) buf[k++] = x[j]
            buf.copyOf(k).sorted().let { s -> if (k % 2 == 1) s[k / 2] else (s[k / 2 - 1] + s[k / 2]) / 2 }
        }
    }

    private fun smooth(x: DoubleArray): DoubleArray {
        val half = T.SMOOTH_BINS / 2
        return DoubleArray(x.size) { i ->
            if (x[i].isNaN()) return@DoubleArray Double.NaN
            var k = 0
            var s = 0.0
            for (j in maxOf(0, i - half)..minOf(x.size - 1, i + half)) if (!x[j].isNaN()) { s += x[j]; k++ }
            s / k
        }
    }

    private class WindowPair(val m1: Double, val m2: Double, val a: Double)

    private fun windowPairs(track: HorizontalTrack, windows: List<SpeedWindow>): List<WindowPair> =
        windows.filter { it.moving }.mapNotNull { w -> track.mean(w.startMs, w.endMs)?.let { (m1, m2) -> WindowPair(m1, m2, w.accelMs2) } }

    private fun fitAxis(pairs: List<WindowPair>): AxisFit? {
        val n = pairs.size.toDouble()
        val mm1 = pairs.sumOf { it.m1 } / n
        val mm2 = pairs.sumOf { it.m2 } / n
        val ma = pairs.sumOf { it.a } / n
        var s11 = 0.0
        var s22 = 0.0
        var s12 = 0.0
        var c1 = 0.0
        var c2 = 0.0
        for (p in pairs) {
            val d1 = p.m1 - mm1
            val d2 = p.m2 - mm2
            val da = p.a - ma
            s11 += d1 * d1; s22 += d2 * d2; s12 += d1 * d2; c1 += d1 * da; c2 += d2 * da
        }
        if (s11 + s22 <= 1e-12 * n) return null // no horizontal variation at all
        val det = s11 * s22 - s12 * s12
        // One-dimensional signal (the sensor moved along a single line): Σ is singular, the covariance direction is
        // then the regression direction.
        val singular = det <= 1e-9 * (s11 + s22) * (s11 + s22)
        val w1 = if (singular) c1 else (s22 * c1 - s12 * c2) / det
        val w2 = if (singular) c2 else (s11 * c2 - s12 * c1) / det
        val wn = sqrt(w1 * w1 + w2 * w2).takeIf { it > 1e-12 } ?: return null
        val cos = w1 / wn
        val sin = w2 / wn
        val p = pairs.map { cos * it.m1 + sin * it.m2 }
        val r = correlation(p, pairs.map { it.a }) ?: return null
        return AxisFit(cos, sin, r, pairs.size)
    }

    private fun events(track: HorizontalTrack, axis: AxisFit, windows: List<SpeedWindow>): List<CalibrationEvent> {
        val out = mutableListOf<CalibrationEvent>()
        val along = DoubleArray(track.size) { axis.cos * track.h1[it] + axis.sin * track.h2[it] }
        var i = 0
        while (i < along.size) {
            val sign = signOf(along[i])
            if (sign == 0) { i++; continue }
            var j = i
            while (j + 1 < along.size && signOf(along[j + 1]) == sign) j++
            val bins = j - i + 1
            if (bins * T.BIN_MS >= T.MIN_EVENT_MS) {
                val slice = along.copyOfRange(i, j + 1)
                val peak = if (sign > 0) slice.max() else slice.min()
                val start = track.timeOf(i) - T.BIN_MS / 2
                val end = track.timeOf(j) + T.BIN_MS / 2
                val obd = windows.filter { it.startMs < end && it.endMs > start && signOf(it.accelMs2, any = true) == sign }
                    .maxByOrNull { sign * it.accelMs2 }?.accelMs2
                out += CalibrationEvent(start, end, peak, slice.average(), obd)
            }
            i = j + 1
        }
        return out
    }

    /** +1 harsh acceleration, −1 harsh braking, 0 otherwise; with [any] just the sign of a non-zero value. */
    private fun signOf(a: Double, any: Boolean = false): Int = when {
        a.isNaN() -> 0
        any -> if (a > 0) 1 else if (a < 0) -1 else 0
        a > StyleThresholds.HARSH_ACCEL_MS2 -> 1
        -a > StyleThresholds.HARSH_BRAKE_MS2 -> -1
        else -> 0
    }

    private fun correlation(x: List<Double>, y: List<Double>): Double? {
        val mx = x.average()
        val my = y.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (k in x.indices) {
            val dx = x[k] - mx
            val dy = y[k] - my
            sxy += dx * dy; sxx += dx * dx; syy += dy * dy
        }
        if (sxx <= 0 || syy <= 0) return null
        return sxy / sqrt(sxx * syy)
    }

    /** Linear-interpolated quantile of a sorted list (Hyndman–Fan type 7). */
    private fun quantile(sorted: List<Double>, q: Double): Double {
        val pos = q * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    private fun norm(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z)
}

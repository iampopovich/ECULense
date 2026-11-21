package io.github.iampopovich.eculense.core.analytics

/** One chart point: time (epoch ms) and value. */
data class ChartPoint(val tMs: Long, val value: Double)

/**
 * Min/max bucket decimation for line charts (the «min-max» downsampling of time-series plotting): the time range
 * [fromMs]..[toMs] is split into `maxPoints / 2` equal buckets and each bucket keeps its minimum and its maximum in
 * time order. A spike shorter than a bucket stays on the chart (plain every-n-th sampling drops it), and the output has
 * at most [maxPoints] points however many values are fed. Streaming: values come page by page in time order from the
 * (sessionId, sensorId, timestampMs) index, memory is O(maxPoints).
 */
class MinMaxDecimator(private val fromMs: Long, private val toMs: Long, maxPoints: Int) {
    init {
        require(maxPoints >= 2) { "maxPoints must be >= 2" }
        require(toMs >= fromMs) { "empty range" }
    }

    private val buckets = maxPoints / 2
    private val minAt = arrayOfNulls<ChartPoint>(buckets)
    private val maxAt = arrayOfNulls<ChartPoint>(buckets)
    var offered = 0L
        private set

    fun offer(tMs: Long, value: Double) {
        if (tMs < fromMs || tMs > toMs || value.isNaN()) return
        offered++
        val span = toMs - fromMs + 1
        val b = (((tMs - fromMs).toDouble() / span) * buckets).toInt().coerceIn(0, buckets - 1)
        val p = ChartPoint(tMs, value)
        val lo = minAt[b]
        if (lo == null || value < lo.value) minAt[b] = p
        val hi = maxAt[b]
        if (hi == null || value > hi.value) maxAt[b] = p
    }

    /** Kept points in time order (min and max of a bucket once each; one point when they coincide). */
    fun points(): List<ChartPoint> {
        val out = ArrayList<ChartPoint>(buckets * 2)
        for (b in 0 until buckets) {
            val lo = minAt[b] ?: continue
            val hi = maxAt[b]!!
            when {
                lo == hi -> out += lo
                lo.tMs <= hi.tMs -> { out += lo; out += hi }
                else -> { out += hi; out += lo }
            }
        }
        return out
    }

    companion object {
        /** Points for a chart about 1080 px wide: two per bucket on ~400 buckets (about 2.7 px per bucket). */
        const val CHART_POINTS = 800

        fun of(points: List<ChartPoint>, maxPoints: Int): List<ChartPoint> {
            if (points.size <= maxPoints) return points.sortedBy { it.tMs }
            val d = MinMaxDecimator(points.minOf { it.tMs }, points.maxOf { it.tMs }, maxPoints)
            points.forEach { d.offer(it.tMs, it.value) }
            return d.points()
        }
    }
}

package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.analytics.ChartPoint
import io.github.iampopovich.eculense.core.analytics.MinMaxDecimator
import io.github.iampopovich.eculense.core.model.UnitsSettings

/** Chart line of one sensor: points in the user's units, [unit] already converted, [sourceCount] readings read. */
data class ChartSeries(val sensorId: String, val unit: String, val points: List<ChartPoint>, val sourceCount: Long)

/**
 * Session chart data without loading the recording into memory: the sensor's time range comes from the index, its
 * readings are streamed page by page into a [MinMaxDecimator] (<= [maxPoints] points, peaks kept) and only the kept
 * points are converted to the user's units ([UnitsSettings.display] is linear and increasing, so min/max survive it).
 */
object SessionChart {
    suspend fun load(
        repository: SessionRepository,
        sessionId: Long,
        sensorId: String,
        units: UnitsSettings,
        window: LongRange? = null,
        maxPoints: Int = MinMaxDecimator.CHART_POINTS,
    ): ChartSeries? {
        val range = repository.sensorRange(sessionId, sensorId) ?: return null
        val unit = units.display(0.0, range.unit).second
        val from = maxOf(range.fromMs, window?.first ?: Long.MIN_VALUE)
        val to = minOf(range.toMs, window?.last ?: Long.MAX_VALUE)
        if (to < from) return ChartSeries(sensorId, unit, emptyList(), 0)
        val d = MinMaxDecimator(from, to, maxPoints)
        repository.forEachSensorReading(sessionId, sensorId, from, to) { d.offer(it.timestampMs, it.value) }
        val points = d.points().map { ChartPoint(it.tMs, units.display(it.value, range.unit).first) }
        return ChartSeries(sensorId, unit, points, d.offered)
    }
}

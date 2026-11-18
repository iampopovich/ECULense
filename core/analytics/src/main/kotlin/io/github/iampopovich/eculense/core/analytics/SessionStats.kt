package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.model.SensorReading
import kotlin.math.ceil
import kotlin.math.roundToInt

data class SensorStats(val sensorId: String, val count: Int, val min: Double, val max: Double, val mean: Double, val p95: Double)

/** Статистика одной сессии по всем датчикам (план 3.1). Для больших выборок — агрегаты SQL + [Percentiles] по прореженной выборке. */
object SessionStats {
    fun compute(readings: List<SensorReading>): List<SensorStats> =
        readings.groupBy { it.sensorId }.map { (id, list) ->
            val values = list.map { it.value }.sorted()
            SensorStats(id, values.size, values.first(), values.last(), values.average(), values[((values.size - 1) * 0.95).toInt()])
        }.sortedBy { it.sensorId }
}

object Percentiles {
    /** Перцентиль «по нижнему индексу», как в [SessionStats]: для 1..100 и q=0.95 → 95. */
    fun of(values: Collection<Double>, q: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * q).toInt()]
    }

    /** Шаг прореживания (каждое step-е значение), чтобы выборка была не больше maxSample. */
    fun step(count: Int, maxSample: Int = 2_000): Int = if (count <= maxSample) 1 else ceil(count.toDouble() / maxSample).toInt()
}

/** Сумма/мин/макс/число — складываются между сессиями без хранения самих значений. */
data class Agg(
    val count: Int = 0,
    val sum: Double = 0.0,
    val min: Double = Double.POSITIVE_INFINITY,
    val max: Double = Double.NEGATIVE_INFINITY,
) {
    val mean: Double? get() = if (count > 0) sum / count else null

    fun add(v: Double) = Agg(count + 1, sum + v, minOf(min, v), maxOf(max, v))

    operator fun plus(o: Agg) = Agg(count + o.count, sum + o.sum, minOf(min, o.min), maxOf(max, o.max))

    companion object {
        val EMPTY = Agg()
    }
}

fun Iterable<Agg>.merged(): Agg = fold(Agg.EMPTY) { a, b -> a + b }

/** Гистограмма с шагом 0,1 — даёт медиану между сессиями (напряжение) без хранения всех значений. */
data class Histogram(val bins: Map<Int, Int> = emptyMap()) {
    val count: Int get() = bins.values.sum()

    fun add(v: Double): Histogram {
        val bin = (v * 10).roundToInt()
        return Histogram(bins + (bin to (bins[bin] ?: 0) + 1))
    }

    operator fun plus(o: Histogram): Histogram {
        val out = bins.toMutableMap()
        o.bins.forEach { (k, n) -> out[k] = (out[k] ?: 0) + n }
        return Histogram(out)
    }

    fun quantile(q: Double): Double? {
        val total = count
        if (total == 0) return null
        val target = ((total - 1) * q).toInt()
        var seen = 0
        for ((bin, n) in bins.toSortedMap()) {
            seen += n
            if (seen > target) return bin / 10.0
        }
        return bins.keys.max() / 10.0
    }

    companion object {
        val EMPTY = Histogram()
    }
}

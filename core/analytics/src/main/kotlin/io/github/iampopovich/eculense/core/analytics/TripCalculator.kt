package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.model.FuelMethod
import io.github.iampopovich.eculense.core.model.FuelType

/** Итоги поездки, посчитанные по записи. */
data class TripMetrics(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val distanceKm: Double,
    val fuelL: Double,
    val fuelMethod: FuelMethod,
    val drivingMs: Long,
    val standingMs: Long,
)

/**
 * Поездка по собственной записи (3.9 п.1). Однопроходный: значения подаются по времени, в памяти — только
 * предыдущая точка каждого ряда, поэтому подходит и для записи на 100 тыс. значений.
 *
 * - путь — интеграл скорости 01.0D методом трапеций; интервалы длиннее [MAX_GAP_MS] (обрыв связи, пауза)
 *   не интегрируются и не считаются ни движением, ни стоянкой;
 * - движение/стоянка — по средней скорости интервала > [MOVING_KMH];
 * - топливо — интеграл 01.5E (л/ч), иначе оценка по MAF (г/с) для бензина: топливо = воздух / 14,7,
 *   плотность 0,745 кг/л; для дизеля/газа/электро — [FuelMethod.NOT_AVAILABLE] (у дизеля смесь бедная и
 *   переменная, MAF ничего не говорит о подаче).
 */
object TripCalculator {
    const val MAX_GAP_MS = 10_000L
    const val MOVING_KMH = 2.0
    /** Стехиометрическое соотношение воздух/топливо для бензина. */
    const val PETROL_AFR = 14.7
    /** Плотность бензина, г/л (≈ 0,72–0,775 по ГОСТ 32513; середина диапазона). */
    const val PETROL_DENSITY_G_PER_L = 745.0

    fun compute(points: Iterable<Triple<String, Double, Long>>, fuelType: FuelType?): TripMetrics? {
        val acc = Accumulator(fuelType)
        points.forEach { (id, v, t) -> acc.accept(id, v, t) }
        return acc.build()
    }

    /** Топливо можно оценить по MAF, если машина бензиновая или тип топлива не указан (чаще всего бензин). */
    fun mafEstimateAllowed(fuelType: FuelType?): Boolean = fuelType == null || fuelType == FuelType.PETROL || fuelType == FuelType.HYBRID

    class Accumulator(private val fuelType: FuelType?) {
        private val speed = Integral()
        private val fuelRate = Integral()
        private val maf = Integral()
        private var drivingMs = 0L
        private var standingMs = 0L

        fun accept(sensorId: String, value: Double, timestampMs: Long) {
            when (sensorId) {
                SensorIds.SPEED -> speed.add(value, timestampMs) { dt, avg ->
                    if (avg > MOVING_KMH) drivingMs += dt else standingMs += dt
                }
                SensorIds.FUEL_RATE -> fuelRate.add(value, timestampMs)
                SensorIds.MAF -> maf.add(value, timestampMs)
            }
        }

        fun build(): TripMetrics? {
            if (speed.samples < 2) return null
            // скорость км/ч × мс → км: / 3 600 000
            val distanceKm = speed.area / 3_600_000.0
            val (fuelL, method) = when {
                fuelRate.samples >= 2 -> fuelRate.area / 3_600_000.0 to FuelMethod.OBD_FUEL_RATE
                maf.samples >= 2 && mafEstimateAllowed(fuelType) ->
                    maf.area / 1000.0 / PETROL_AFR / PETROL_DENSITY_G_PER_L to FuelMethod.MAF_ESTIMATE
                else -> 0.0 to FuelMethod.NOT_AVAILABLE
            }
            return TripMetrics(speed.firstMs!!, speed.lastMs!!, distanceKm, fuelL, method, drivingMs, standingMs)
        }
    }

    /** Трапеции по одному ряду; area — в «единицах × мс». */
    private class Integral {
        var samples = 0
        var area = 0.0
        var firstMs: Long? = null
        var lastMs: Long? = null
        private var lastValue = 0.0

        fun add(value: Double, t: Long, onInterval: (dtMs: Long, avg: Double) -> Unit = { _, _ -> }) {
            if (value.isNaN()) return
            val prev = lastMs
            if (prev != null && t < prev) return
            if (prev != null) {
                val dt = t - prev
                if (dt in 1..MAX_GAP_MS) {
                    val avg = (lastValue + value) / 2
                    area += avg * dt
                    onInterval(dt, avg)
                }
            } else {
                firstMs = t
            }
            samples++
            lastMs = t
            lastValue = value
        }
    }
}

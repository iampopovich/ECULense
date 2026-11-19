package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.model.FuelMethod
import io.github.iampopovich.eculense.core.model.FuelType

/** Trip totals computed from a recording. */
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
 * Trip from our own recording (3.9 item 1). Single-pass: values are fed in time order, only the previous point
 * of each series is kept in memory, so it also works for a recording with 100k values.
 *
 * - distance — trapezoidal integral of speed 01.0D; intervals longer than [MAX_GAP_MS] (link loss, pause)
 *   are not integrated and count as neither moving nor stopped;
 * - moving/stopped — by the interval's average speed > [MOVING_KMH];
 * - fuel — integral of 01.5E (L/h), otherwise a MAF-based estimate (g/s) for gasoline: fuel = air / 14.7,
 *   density 0.745 kg/L; for diesel/gas/electric — [FuelMethod.NOT_AVAILABLE] (diesel runs a lean, variable
 *   mixture, so MAF says nothing about fuel delivery).
 */
object TripCalculator {
    const val MAX_GAP_MS = 10_000L
    const val MOVING_KMH = 2.0
    /** Stoichiometric air/fuel ratio for gasoline. */
    const val PETROL_AFR = 14.7
    /** Gasoline density, g/L (≈ 0.72–0.775 per GOST 32513; middle of the range). */
    const val PETROL_DENSITY_G_PER_L = 745.0

    fun compute(points: Iterable<Triple<String, Double, Long>>, fuelType: FuelType?): TripMetrics? {
        val acc = Accumulator(fuelType)
        points.forEach { (id, v, t) -> acc.accept(id, v, t) }
        return acc.build()
    }

    /** Fuel can be estimated from MAF if the car is gasoline or the fuel type is unspecified (most often gasoline). */
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
            // speed km/h × ms → km: / 3 600 000
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

    /** Trapezoids over one series; area is in "units × ms". */
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

package io.github.iampopovich.eculense.core.model

/** Distance unit chosen by the user; labels are string resources in :core:ui. */
enum class DistanceUnit { KM, MI }

/** Volume unit chosen by the user. */
enum class VolumeUnit { L, GAL_US, GAL_IMP }

/** Fuel consumption unit chosen by the user. */
enum class ConsumptionUnit { L_PER_100KM, MPG_US, MPG_IMP }

/** A converted value with its unit. */
data class Measured<U>(val value: Double, val unit: U)

/**
 * Units of measurement (counterpart of Car Scanner flags Use_km, Use_celcium, UseL100ForFuel …).
 * Values in the DB are always metric; conversion happens only for display and export.
 */
data class UnitsSettings(
    val metricDistance: Boolean = true,
    val celsius: Boolean = true,
    val litersPer100Km: Boolean = true,
    val liters: Boolean = true,
    val usGallon: Boolean = true,
    val pressureKpa: Boolean = true,
    val horsePower: Boolean = true,
    val newtonMeters: Boolean = true,
    val flowGramsPerSecond: Boolean = true,
    val accelerationG: Boolean = false,
) {
    /** Converts a sensor reading from its metric unit to the one chosen by the user (sensor units are symbols, not words). */
    fun display(value: Double, unit: String): Pair<Double, String> = when {
        unit == "km/h" && !metricDistance -> value / KM_PER_MILE to "mph"
        unit == "km" && !metricDistance -> value / KM_PER_MILE to "mi"
        unit == "°C" && !celsius -> value * 9.0 / 5.0 + 32.0 to "°F"
        unit == "kPa" && !pressureKpa -> value * PSI_PER_KPA to "psi"
        else -> value to unit
    }

    /** Fuel consumption: L/100 km or miles per gallon (US/Imp). */
    fun consumption(lPer100Km: Double): Measured<ConsumptionUnit> = when {
        litersPer100Km || lPer100Km <= 0.0 -> Measured(lPer100Km, ConsumptionUnit.L_PER_100KM)
        usGallon -> Measured(MPG_US / lPer100Km, ConsumptionUnit.MPG_US)
        else -> Measured(MPG_IMP / lPer100Km, ConsumptionUnit.MPG_IMP)
    }

    fun distance(km: Double): Measured<DistanceUnit> =
        if (metricDistance) Measured(km, DistanceUnit.KM) else Measured(km / KM_PER_MILE, DistanceUnit.MI)

    fun volume(liters: Double): Measured<VolumeUnit> = when {
        this.liters -> Measured(liters, VolumeUnit.L)
        usGallon -> Measured(liters / L_PER_US_GAL, VolumeUnit.GAL_US)
        else -> Measured(liters / L_PER_IMP_GAL, VolumeUnit.GAL_IMP)
    }

    companion object {
        const val KM_PER_MILE = 1.609344
        const val PSI_PER_KPA = 0.1450377
        const val L_PER_US_GAL = 3.785411784
        const val L_PER_IMP_GAL = 4.54609
        private const val MPG_US = 235.214583
        private const val MPG_IMP = 282.480936
    }
}

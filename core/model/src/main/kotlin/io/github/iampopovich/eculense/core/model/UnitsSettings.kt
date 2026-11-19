package io.github.iampopovich.eculense.core.model

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
    /** Converts a sensor reading from its metric unit to the one chosen by the user. */
    fun display(value: Double, unit: String): Pair<Double, String> = when {
        unit == "km/h" && !metricDistance -> value / KM_PER_MILE to "mph"
        unit == "km" && !metricDistance -> value / KM_PER_MILE to "mi"
        unit == "°C" && !celsius -> value * 9.0 / 5.0 + 32.0 to "°F"
        unit == "kPa" && !pressureKpa -> value * PSI_PER_KPA to "psi"
        else -> value to unit
    }

    /** Fuel consumption: L/100 km or miles per gallon (US/Imp). */
    fun consumption(lPer100Km: Double): Pair<Double, String> = when {
        litersPer100Km || lPer100Km <= 0.0 -> lPer100Km to "л/100 км"
        else -> (if (usGallon) MPG_US else MPG_IMP) / lPer100Km to if (usGallon) "mpg (US)" else "mpg (Imp)"
    }

    fun distance(km: Double): Pair<Double, String> = if (metricDistance) km to "км" else km / KM_PER_MILE to "ми"

    fun volume(liters: Double): Pair<Double, String> = when {
        this.liters -> liters to "л"
        usGallon -> liters / L_PER_US_GAL to "гал (US)"
        else -> liters / L_PER_IMP_GAL to "гал (Imp)"
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

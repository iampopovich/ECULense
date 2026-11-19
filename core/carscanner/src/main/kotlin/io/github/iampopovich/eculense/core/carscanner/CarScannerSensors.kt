package io.github.iampopovich.eculense.core.carscanner

/**
 * Mapping of Car Scanner internal sensor ids to our sensorId.
 * Verified against a real backup: sensor name + value range. Other ids are stored as "cs.<id>".
 */
object CarScannerSensors {
    private val obd = mapOf(
        4 to "01.04", 5 to "01.05", 6 to "01.06", 7 to "01.07", 8 to "01.08", 9 to "01.09",
        12 to "01.0C", 13 to "01.0D", 14 to "01.0E", 15 to "01.0F", 16 to "01.10", 17 to "01.11",
        41 to "01.21",
    )

    /** Format code → unit under the user's backup settings: km, °C, L/100 km, hp, g, pressure not in kPa. */
    private val units = mapOf(
        1 to "km/h", 3 to "km", 6 to "rpm", 7 to "°", 8 to "g/s", 10 to "V", 12 to "L/h", 14 to "%",
        15 to "°C", 20 to "m", 22 to "L/100km", 24 to "L", 43 to "g", 45 to "hp", 46 to "hp", 47 to "psi",
    )

    fun sensorId(csId: Int): String = obd[csId] ?: if (csId == 0) "elm.voltage" else "cs.$csId"

    fun unit(format: Int): String = units[format].orEmpty()

    /** Raw sensors. From 229 on come derived ones (consumption, cost, daily/weekly mileage) and GPS — we skip them. */
    fun isImported(csId: Int): Boolean = csId < 229
}

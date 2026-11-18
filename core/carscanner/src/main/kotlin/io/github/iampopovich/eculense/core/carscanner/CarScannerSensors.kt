package io.github.iampopovich.eculense.core.carscanner

/**
 * Сопоставление внутренних id датчиков Car Scanner с нашими sensorId.
 * Сверено по реальному бэкапу: имя датчика + диапазон значений. Остальные id сохраняются как "cs.<id>".
 */
object CarScannerSensors {
    private val obd = mapOf(
        4 to "01.04", 5 to "01.05", 6 to "01.06", 7 to "01.07", 8 to "01.08", 9 to "01.09",
        12 to "01.0C", 13 to "01.0D", 14 to "01.0E", 15 to "01.0F", 16 to "01.10", 17 to "01.11",
        41 to "01.21",
    )

    /** Код формата → единица при настройках из бэкапа пользователя: км, °C, л/100 км, л.с., g, давление не в кПа. */
    private val units = mapOf(
        1 to "km/h", 3 to "km", 6 to "rpm", 7 to "°", 8 to "g/s", 10 to "V", 12 to "L/h", 14 to "%",
        15 to "°C", 20 to "m", 22 to "L/100km", 24 to "L", 43 to "g", 45 to "hp", 46 to "hp", 47 to "psi",
    )

    fun sensorId(csId: Int): String = obd[csId] ?: if (csId == 0) "elm.voltage" else "cs.$csId"

    fun unit(format: Int): String = units[format].orEmpty()

    /** «Сырые» датчики. С 229 начинаются производные (расход, стоимость, пробег за день/неделю) и GPS — их не берём. */
    fun isImported(csId: Int): Boolean = csId < 229
}

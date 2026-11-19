package io.github.iampopovich.eculense.core.analytics

/**
 * Sensor IDs needed by analytics. Same format as in the DB: "01.05" = service 01, PID 0x05.
 * Car Scanner import stores fuel trims under the same PIDs 01.06–01.09, while voltage is often
 * only "elm.voltage" (the adapter's ATRV command) — the rules understand both sources.
 */
object SensorIds {
    const val COOLANT = "01.05"
    const val STFT1 = "01.06"
    const val LTFT1 = "01.07"
    const val STFT2 = "01.08"
    const val LTFT2 = "01.09"
    const val RPM = "01.0C"
    const val SPEED = "01.0D"
    const val IAT = "01.0F"
    const val MAF = "01.10"
    const val MIL_DISTANCE = "01.21"
    /** Engine fuel rate, L/h (PID 0x5E) — not available on every car. */
    const val FUEL_RATE = "01.5E"
    /** Control module voltage (PID 0x42) — more accurate than the adapter's. */
    const val MODULE_VOLTAGE = "01.42"
    /** Voltage at the OBD connector via ATRV — present in almost every recording. */
    const val ELM_VOLTAGE = "elm.voltage"

    val TRIMS = listOf(STFT1, LTFT1, STFT2, LTFT2)

    /** Everything [SessionDigester] reads: the rules need no other sensors, and those are not loaded from the DB. */
    val FOR_RULES: Set<String> = setOf(COOLANT, RPM, SPEED, MAF, MIL_DISTANCE, MODULE_VOLTAGE, ELM_VOLTAGE) + TRIMS

    /** Labels for the AI and the screen; unknown ids are shown as is. */
    val NAMES: Map<String, String> = mapOf(
        "01.04" to "Нагрузка двигателя",
        COOLANT to "Температура ОЖ",
        STFT1 to "Краткосрочная коррекция топлива, банк 1",
        LTFT1 to "Долгосрочная коррекция топлива, банк 1",
        STFT2 to "Краткосрочная коррекция топлива, банк 2",
        LTFT2 to "Долгосрочная коррекция топлива, банк 2",
        RPM to "Обороты",
        SPEED to "Скорость",
        "01.0E" to "Угол опережения зажигания",
        IAT to "Температура впускного воздуха",
        MAF to "Массовый расход воздуха",
        "01.11" to "Положение дроссельной заслонки",
        MIL_DISTANCE to "Пробег с горящим Check Engine",
        MODULE_VOLTAGE to "Напряжение блока управления",
        ELM_VOLTAGE to "Напряжение на разъёме OBD (адаптер)",
    )

    /** Coordinates and any other location traces never leave the app (AI tools, summary export). */
    fun isLocation(sensorId: String): Boolean {
        val id = sensorId.lowercase()
        return id.startsWith("gps") || id.contains("lat") || id.contains("lon") || id.contains("location") || id.contains("altitude")
    }
}

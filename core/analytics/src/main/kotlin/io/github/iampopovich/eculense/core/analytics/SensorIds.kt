package io.github.iampopovich.eculense.core.analytics

/**
 * Идентификаторы датчиков, которые нужны аналитике. Формат как в БД: "01.05" = сервис 01, PID 0x05.
 * Импорт Car Scanner кладёт коррекции топлива под теми же PID 01.06–01.09, а напряжение часто
 * только как "elm.voltage" (команда ATRV адаптера) — правила понимают оба источника.
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
    /** Расход топлива двигателя, л/ч (PID 0x5E) — есть не у всех машин. */
    const val FUEL_RATE = "01.5E"
    /** Напряжение блока управления (PID 0x42) — точнее, чем у адаптера. */
    const val MODULE_VOLTAGE = "01.42"
    /** Напряжение на разъёме OBD по ATRV — есть почти в каждой записи. */
    const val ELM_VOLTAGE = "elm.voltage"

    val TRIMS = listOf(STFT1, LTFT1, STFT2, LTFT2)

    /** Всё, что читает [SessionDigester]: остальные датчики правилам не нужны и из БД не грузятся. */
    val FOR_RULES: Set<String> = setOf(COOLANT, RPM, SPEED, MAF, MIL_DISTANCE, MODULE_VOLTAGE, ELM_VOLTAGE) + TRIMS

    /** Подписи для ИИ и экрана; неизвестные id показываются как есть. */
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

    /** Координаты и прочие следы местоположения никогда не уходят наружу (инструменты ИИ, экспорт сводок). */
    fun isLocation(sensorId: String): Boolean {
        val id = sensorId.lowercase()
        return id.startsWith("gps") || id.contains("lat") || id.contains("lon") || id.contains("location") || id.contains("altitude")
    }
}

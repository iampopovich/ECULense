package io.github.iampopovich.eculense.core.obd

/** PID сервиса 01. decode получает только байты данных (без 41 и номера PID). */
class Pid(
    val code: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    private val decode: (IntArray) -> Double,
) {
    val sensorId: String get() = "01.%02X".format(code)
    val command: String get() = "01%02X".format(code)

    fun decodeFrom(message: ByteArray): Double? {
        val u = message.toUnsigned()
        if (u.size < 2 + bytes || u[0] != 0x41 || u[1] != code) return null
        return decode(u.subList(2, 2 + bytes).toIntArray())
    }

    override fun toString() = "Pid($sensorId $name)"
}

object StandardPids {
    private fun pct(a: Int) = a * 100.0 / 255
    private fun trim(a: Int) = a * 100.0 / 128 - 100
    private fun word(d: IntArray) = d[0] * 256 + d[1]

    val all: List<Pid> = listOf(
        Pid(0x04, "Нагрузка двигателя", "%", 1) { pct(it[0]) },
        Pid(0x05, "Температура ОЖ", "°C", 1) { it[0] - 40.0 },
        Pid(0x06, "Краткосрочная коррекция топлива, банк 1", "%", 1) { trim(it[0]) },
        Pid(0x07, "Долгосрочная коррекция топлива, банк 1", "%", 1) { trim(it[0]) },
        Pid(0x0B, "Давление во впускном коллекторе", "kPa", 1) { it[0].toDouble() },
        Pid(0x0C, "Обороты двигателя", "rpm", 2) { word(it) / 4.0 },
        Pid(0x0D, "Скорость", "km/h", 1) { it[0].toDouble() },
        Pid(0x0E, "Угол опережения зажигания", "°", 1) { it[0] / 2.0 - 64 },
        Pid(0x0F, "Температура воздуха на впуске", "°C", 1) { it[0] - 40.0 },
        Pid(0x10, "Массовый расход воздуха", "g/s", 2) { word(it) / 100.0 },
        Pid(0x11, "Положение дроссельной заслонки", "%", 1) { pct(it[0]) },
        Pid(0x1F, "Время с запуска двигателя", "s", 2) { word(it).toDouble() },
        Pid(0x21, "Пробег с горящим Check Engine", "km", 2) { word(it).toDouble() },
        Pid(0x2F, "Уровень топлива", "%", 1) { pct(it[0]) },
        Pid(0x31, "Пробег после сброса ошибок", "km", 2) { word(it).toDouble() },
        Pid(0x33, "Атмосферное давление", "kPa", 1) { it[0].toDouble() },
        Pid(0x42, "Напряжение бортсети (ЭБУ)", "V", 2) { word(it) / 1000.0 },
        Pid(0x46, "Температура окружающего воздуха", "°C", 1) { it[0] - 40.0 },
        Pid(0x5C, "Температура масла", "°C", 1) { it[0] - 40.0 },
        Pid(0x5E, "Расход топлива", "L/h", 2) { word(it) / 20.0 },
    )

    /** Что показывает панель по умолчанию, в порядке важности (если ЭБУ поддерживает). */
    val dashboardDefaults: List<Int> = listOf(0x0C, 0x0D, 0x05, 0x04, 0x11, 0x42, 0x0F, 0x10)

    private val index = all.associateBy { it.code }

    fun byCode(code: Int): Pid? = index[code]
}

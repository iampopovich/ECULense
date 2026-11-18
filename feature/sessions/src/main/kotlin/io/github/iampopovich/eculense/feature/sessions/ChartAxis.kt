package io.github.iampopovich.eculense.feature.sessions

/** Круглые шаги оси времени, секунды. */
private val niceSteps = listOf(1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, 3600.0)

/**
 * Шаг оси X для графика поездки: около шести подписей на всю длительность.
 * Без него Vico берёт НОД разностей x (доли секунды) — график растягивается на тысячи шагов и показывает первые миллисекунды.
 */
internal fun niceXStepSeconds(durationSec: Double): Double {
    val target = durationSec / 6
    return niceSteps.firstOrNull { it >= target } ?: niceSteps.last()
}

/** По умолчанию — обороты, если их записывали (как в плане: «график оборотов»), иначе первый датчик. */
internal fun defaultSensor(sensorIds: List<String>): String? =
    sensorIds.firstOrNull { it == "01.0C" } ?: sensorIds.firstOrNull()

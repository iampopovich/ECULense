package io.github.iampopovich.eculense.feature.sessions

/** Round time-axis steps, in seconds. */
private val niceSteps = listOf(1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, 3600.0)

/**
 * X-axis step for the trip chart: about six labels across the whole duration.
 * Without it Vico uses the GCD of x differences (fractions of a second): the chart stretches to thousands of steps and shows the first milliseconds.
 */
internal fun niceXStepSeconds(durationSec: Double): Double {
    val target = durationSec / 6
    return niceSteps.firstOrNull { it >= target } ?: niceSteps.last()
}

/** Defaults to RPM if it was recorded (as in the plan: "RPM chart"), otherwise the first sensor. */
internal fun defaultSensor(sensorIds: List<String>): String? =
    sensorIds.firstOrNull { it == "01.0C" } ?: sensorIds.firstOrNull()

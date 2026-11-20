package io.github.iampopovich.eculense.core.model

/**
 * B3 freeze frame (service 02, frame 0): the conditions the ECU saved when [dtcCode] was stored. [readings] use the
 * service 01 sensor ids ("01.0C") and units, so they render like live values. dtcCode null = the ECU did not name it.
 */
data class FreezeFrame(val dtcCode: String?, val readings: List<SensorReading>) {
    fun value(sensorId: String): Double? = readings.firstOrNull { it.sensorId == sensorId }?.value
}

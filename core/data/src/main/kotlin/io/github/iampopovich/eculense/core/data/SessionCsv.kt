package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.model.SensorReading

object SessionCsv {
    const val HEADER = "timestamp_ms;sensor_id;value;unit"

    fun write(readings: List<SensorReading>, out: Appendable) {
        out.append(HEADER).append('\n')
        readings.forEach { out.append("${it.timestampMs};${it.sensorId};${it.value};${it.unit}\n") }
    }
}

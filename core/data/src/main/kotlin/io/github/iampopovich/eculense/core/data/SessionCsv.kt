package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.UnitsSettings

object SessionCsv {
    const val HEADER = "timestamp_ms;sensor_id;value;unit"

    /** Values are converted to the user's units (metric in the DB). */
    fun write(readings: List<SensorReading>, out: Appendable, units: UnitsSettings = UnitsSettings()) {
        out.append(HEADER).append('\n')
        readings.forEach {
            val (value, unit) = units.display(it.value, it.unit)
            out.append("${it.timestampMs};${it.sensorId};$value;$unit\n")
        }
    }
}

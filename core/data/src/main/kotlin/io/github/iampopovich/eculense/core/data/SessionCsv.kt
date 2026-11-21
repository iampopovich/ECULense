package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.UnitsSettings

object SessionCsv {
    const val HEADER = "timestamp_ms;sensor_id;value;unit"

    /** Values are converted to the user's units (metric in the DB). */
    fun write(readings: List<SensorReading>, out: Appendable, units: UnitsSettings = UnitsSettings()) {
        out.append(HEADER).append('\n')
        readings.forEach { row(it, out, units) }
    }

    /**
     * Streaming export: the readings come page by page from [SessionRepository.forEachReading] straight into [out]
     * (a buffered file writer), so a 100k+ reading recording is never held in memory. Returns the number of rows.
     */
    suspend fun write(repository: SessionRepository, sessionId: Long, out: Appendable, units: UnitsSettings = UnitsSettings()): Long {
        out.append(HEADER).append('\n')
        var rows = 0L
        repository.forEachReading(sessionId) {
            row(it, out, units)
            rows++
        }
        return rows
    }

    private fun row(r: SensorReading, out: Appendable, units: UnitsSettings) {
        val (value, unit) = units.display(r.value, r.unit)
        out.append("${r.timestampMs};${r.sensorId};$value;$unit\n")
    }
}

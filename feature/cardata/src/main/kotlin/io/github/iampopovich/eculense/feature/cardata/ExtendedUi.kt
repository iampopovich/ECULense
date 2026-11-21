package io.github.iampopovich.eculense.feature.cardata

import io.github.iampopovich.eculense.core.data.ExtendedSignal
import io.github.iampopovich.eculense.core.data.ExtendedStatus
import io.github.iampopovich.eculense.core.model.UnitsSettings
import io.github.iampopovich.eculense.core.obdb.EvBattery
import io.github.iampopovich.eculense.core.obdb.EvRole

/** One extended (OBDb) sensor row; [value] in display units, null = no value yet. */
internal data class ExtendedRow(val signal: ExtendedSignal, val value: Double?, val unit: String, val notAnswering: Boolean)

/**
 * R-E: the «Extended sensors (OBDb)» section of Car data. [rows] = sensors with a value (or not answering), grouped by
 * OBDb path; [waiting] = sensors of the signalset without a value yet (counted, not listed: an EV signalset has
 * hundreds of cell voltages). [ev] = the battery card (null when the link is not an EV or nothing was read).
 */
internal data class ExtendedUi(
    val enabled: Boolean = false,
    val status: ExtendedStatus = ExtendedStatus.NotConnected,
    val rows: List<ExtendedRow> = emptyList(),
    val waiting: Int = 0,
    val ev: EvBattery? = null,
    val units: UnitsSettings = UnitsSettings(),
) {
    val ready: ExtendedStatus.Ready? get() = status as? ExtendedStatus.Ready

    companion object {
        /** Builds the section from the status, the switch and the latest raw readings by sensor id. */
        fun of(enabled: Boolean, status: ExtendedStatus, latest: Map<String, Double>, disabledCommands: Set<String>, units: UnitsSettings): ExtendedUi {
            val ready = status as? ExtendedStatus.Ready ?: return ExtendedUi(enabled, status, units = units)
            if (!enabled) return ExtendedUi(enabled, status, units = units)
            val rows = ready.signals.mapNotNull { s ->
                val raw = latest[s.sensorId]
                val off = s.commandId in disabledCommands
                if (raw == null && !off) return@mapNotNull null
                val (v, u) = raw?.let { units.display(it, s.unit) } ?: (null to units.display(0.0, s.unit).second)
                ExtendedRow(s, v, u, notAnswering = off && raw == null)
            }.sortedWith(compareBy({ it.signal.path ?: "" }, { it.signal.name }))
            val values = ready.signals.mapNotNull { s -> s.role?.let { r -> latest[s.sensorId]?.let { r to it } } }.toMap<EvRole, Double>()
            val ev = if (ready.ev) EvBattery.of(values).takeIf { !it.isEmpty } else null
            return ExtendedUi(enabled, status, rows, ready.signals.size - rows.size, ev, units)
        }
    }
}

package io.github.iampopovich.eculense.feature.widget

import io.github.iampopovich.eculense.core.data.LastSeen
import io.github.iampopovich.eculense.core.model.DistanceUnit
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.UnitsSettings
import io.github.iampopovich.eculense.core.obd.StandardPids

/** One live value on the widget, already in the user's units ([UnitsSettings.display]). */
internal data class WidgetValue(val code: Int, val value: Double?, val unit: String)

/** What the widget shows; pure data, rendered by [WidgetContent]. */
internal sealed interface WidgetModel {
    /** Connected: the first values of the dashboard selection. [simulated] = demo/replay (display only). */
    data class Live(val values: List<WidgetValue>, val simulated: Boolean) : WidgetModel

    /** Not connected: the last known facts of the active car with their age. */
    data class Idle(
        val voltage: Double? = null,
        val voltageAgeMs: Long? = null,
        val dtcCount: Int? = null,
        val dtcAgeMs: Long? = null,
        /** Last trip distance in [distanceUnit]. */
        val tripDistance: Double? = null,
        val distanceUnit: DistanceUnit = DistanceUnit.KM,
        val tripAgeMs: Long? = null,
    ) : WidgetModel {
        val empty: Boolean get() = voltage == null && dtcCount == null && tripDistance == null
    }
}

internal object WidgetModels {
    /** At most this many live values (4x2); smaller sizes show fewer. */
    const val MAX_VALUES = 3

    /** While connected the widget is redrawn at most this often (connection changes are immediate). */
    const val LIVE_EVERY_MS = 10_000L

    fun live(selection: List<Int>?, supported: Set<Int>, latest: Map<String, Double>, simulated: Boolean, units: UnitsSettings = UnitsSettings()): WidgetModel.Live {
        val codes = (selection ?: StandardPids.dashboardDefaults)
            .filter { supported.isEmpty() || it in supported }
            .take(MAX_VALUES)
        val values = codes.mapNotNull { code ->
            StandardPids.byCode(code)?.let { pid ->
                val value = latest[pid.sensorId]
                WidgetValue(code, value?.let { units.display(it, pid.unit).first }, units.display(0.0, pid.unit).second)
            }
        }
        return WidgetModel.Live(values, simulated)
    }

    fun idle(seen: LastSeen, lastTrip: Trip?, nowMs: Long, units: UnitsSettings = UnitsSettings()): WidgetModel.Idle = WidgetModel.Idle(
        voltage = seen.voltage,
        voltageAgeMs = seen.voltageAtMs?.let { (nowMs - it).coerceAtLeast(0) },
        dtcCount = seen.dtcCount,
        dtcAgeMs = seen.dtcCheckedAtMs?.let { (nowMs - it).coerceAtLeast(0) },
        tripDistance = lastTrip?.distanceKm?.let { units.distance(it).value },
        distanceUnit = units.distance(0.0).unit,
        tripAgeMs = lastTrip?.let { (nowMs - it.endedAtMs).coerceAtLeast(0) },
    )
}

/** Age bucket for the «… ago» texts (resources live in the widget module). */
internal sealed interface Age {
    data object Now : Age
    data class Minutes(val n: Int) : Age
    data class Hours(val n: Int) : Age
    data class Days(val n: Int) : Age

    companion object {
        fun of(ms: Long): Age {
            val min = ms / 60_000
            return when {
                min < 1 -> Now
                min < 60 -> Minutes(min.toInt())
                min < 48 * 60 -> Hours((min / 60).toInt())
                else -> Days((min / (24 * 60)).toInt())
            }
        }
    }
}

/**
 * C9 update cadence: a connection change pushes at once, live values at most every [WidgetModels.LIVE_EVERY_MS];
 * nothing is scheduled while idle.
 */
internal class WidgetThrottle(private val everyMs: Long = WidgetModels.LIVE_EVERY_MS) {
    private var lastMs = Long.MIN_VALUE

    fun shouldPush(nowMs: Long, connectionChanged: Boolean): Boolean {
        val due = connectionChanged || lastMs == Long.MIN_VALUE || nowMs - lastMs >= everyMs
        if (due) lastMs = nowMs
        return due
    }
}

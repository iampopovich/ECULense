package io.github.iampopovich.eculense.feature.dashboard

import io.github.iampopovich.eculense.core.analytics.TripCalculator
import io.github.iampopovich.eculense.core.data.DashboardSelection
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.Pid
import io.github.iampopovich.eculense.core.obd.PollTiers
import io.github.iampopovich.eculense.core.obd.StandardPids

/** C3: what a live value tile shows. Missing data is never a 0. */
internal enum class ValueStatus {
    /** «—»: nothing received yet. */
    NO_DATA,

    /** «—» + «not answering»: the PID was switched off for this connection after repeated NO DATA (A9). */
    NOT_ANSWERING,

    /** Fresh value. */
    LIVE,

    /** Dimmed with an age label: older than 3 tier periods, or the link is reconnecting / the engine is off. */
    STALE,

    /** «○»: fuel trim while the fuel system runs in open loop (PID 03) — the trim is not used now. */
    OPEN_LOOP,
}

internal data class TileState(
    val pid: Pid,
    /** Display units (converted); null when [status] has no number to show. */
    val value: Double?,
    val unit: String,
    val status: ValueStatus,
    /** «~»: computed, not reported by the car (fuel rate from MAF). */
    val estimated: Boolean = false,
    /** Seconds since the value arrived, for STALE tiles. */
    val ageSec: Long? = null,
    /** Last 60 s, oldest first, in stored (metric) units; only the shape matters. */
    val spark: List<Float> = emptyList(),
    val decimals: Int = decimalsOf(pid),
    /** C4: a live alert is active for this sensor (error color). */
    val alert: Boolean = false,
    /** D3: «~230 km» left, only on the fuel-level tile when the tank capacity and a learned consumption are known. */
    val rangeKm: Double? = null,
)

internal data class Stamped(val value: Double, val atMs: Long)

/** Number of decimals per PID: integers for rpm, speed, temperatures, percentages of position. */
internal fun decimalsOf(pid: Pid): Int = when (pid.code) {
    0x42, 0x10, 0x5E, 0x06, 0x07, 0x08, 0x09, 0x0E -> 1
    0x14, 0x15, 0x44 -> 2
    else -> 0
}

/**
 * Latest values and a 60 s history per sensor. Not thread-safe: fed and read on the main thread by the ViewModel.
 */
internal class LiveBuffer(private val windowMs: Long = SPARK_WINDOW_MS) {
    private val latest = HashMap<String, Stamped>()
    private val history = HashMap<String, ArrayDeque<Stamped>>()

    fun accept(reading: SensorReading, nowMs: Long) {
        val s = Stamped(reading.value, nowMs)
        latest[reading.sensorId] = s
        val h = history.getOrPut(reading.sensorId) { ArrayDeque() }
        h.addLast(s)
        while (h.isNotEmpty() && nowMs - h.first().atMs > windowMs) h.removeFirst()
    }

    fun latest(sensorId: String): Stamped? = latest[sensorId]

    /** History downsampled to at most [points] values (last value of each time bucket). */
    fun spark(sensorId: String, nowMs: Long, points: Int = SPARK_POINTS): List<Float> {
        val h = history[sensorId] ?: return emptyList()
        val from = nowMs - windowMs
        val bucketMs = windowMs / points
        val out = ArrayList<Float>(points)
        var lastBucket = Long.MIN_VALUE
        for (s in h) {
            if (s.atMs < from) continue
            val bucket = (s.atMs - from) / bucketMs
            if (bucket == lastBucket) out[out.lastIndex] = s.value.toFloat() else out += s.value.toFloat()
            lastBucket = bucket
        }
        return out
    }

    fun clear() {
        latest.clear()
        history.clear()
    }

    companion object {
        const val SPARK_WINDOW_MS = 60_000L
        const val SPARK_POINTS = 60
    }
}

/** Pure tile model (C3): state per tile from the buffer, the connection state and the poller's facts. */
internal object TileModel {
    private const val MIN_STALE_MS = 3_000L
    private const val FALLBACK_CYCLE_MS = 1_000L

    /** A value is stale after 3 periods of its tier (cycle time from PollStats). */
    fun staleAfterMs(pid: Pid, cycleMs: Long?): Long =
        maxOf(MIN_STALE_MS, 3 * PollTiers.tierOf(pid.code).everyCycles * (cycleMs?.takeIf { it > 0 } ?: FALLBACK_CYCLE_MS))

    /** MAF g/s → L/h for petrol (stoichiometric AFR and density from the trip calculator). */
    fun fuelRateFromMaf(mafGs: Double): Double =
        mafGs * 3_600 / (TripCalculator.PETROL_AFR * TripCalculator.PETROL_DENSITY_G_PER_L)

    fun build(
        pids: List<Pid>,
        buffer: LiveBuffer,
        state: ConnectionState,
        disabled: Set<Int>,
        cycleMs: Long?,
        nowMs: Long,
        estimateFuel: Boolean = true,
        convert: (Double, String) -> Pair<Double, String>,
    ): List<TileState> {
        val paused = state is ConnectionState.Reconnecting || state is ConnectionState.EngineOff
        val fuelStatus = buffer.latest(StandardPids.byCode(0x03)!!.sensorId)
        return pids.map { pid ->
            val unit = convert(0.0, pid.unit).second
            val own = buffer.latest(pid.sensorId)
            val maf = buffer.latest(StandardPids.byCode(DashboardSelection.MAF)!!.sensorId)
            val estimate = estimateFuel && pid.code == DashboardSelection.FUEL_RATE && maf != null &&
                (own == null || pid.code in disabled)
            val sample = when {
                estimate -> Stamped(fuelRateFromMaf(maf!!.value), maf.atMs)
                pid.code in disabled -> null
                else -> own
            }
            when {
                sample == null -> TileState(pid, null, unit, if (pid.code in disabled) ValueStatus.NOT_ANSWERING else ValueStatus.NO_DATA)
                else -> {
                    val shown = convert(sample.value, pid.unit).first
                    val age = nowMs - sample.atMs
                    val stale = paused || age > staleAfterMs(if (estimate) StandardPids.byCode(DashboardSelection.MAF)!! else pid, cycleMs)
                    val openLoop = pid.code in StandardPids.FUEL_TRIMS && fuelStatus != null &&
                        !StandardPids.isClosedLoop(fuelStatus.value)
                    val status = when {
                        stale -> ValueStatus.STALE
                        openLoop -> ValueStatus.OPEN_LOOP
                        else -> ValueStatus.LIVE
                    }
                    val sparkId = if (estimate) StandardPids.byCode(DashboardSelection.MAF)!!.sensorId else pid.sensorId
                    TileState(
                        pid = pid,
                        value = shown,
                        unit = unit,
                        status = status,
                        estimated = estimate,
                        ageSec = if (status == ValueStatus.STALE) maxOf(1, age / 1_000) else null,
                        spark = buffer.spark(sparkId, nowMs),
                    )
                }
            }
        }
    }
}

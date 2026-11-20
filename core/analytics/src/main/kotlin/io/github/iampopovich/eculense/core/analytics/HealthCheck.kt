package io.github.iampopovich.eculense.core.analytics

import kotlin.math.abs

/** B6 traffic light per item; NOT_SUPPORTED = «not on this ECU», NOT_JUDGED = the conditions do not allow a verdict. */
enum class HealthStatus(val rank: Int) { NOT_SUPPORTED(0), NOT_JUDGED(1), OK(2), ATTENTION(3), FAULT(4) }

enum class HealthItemId {
    STORED_CODES, PENDING_CODES, PERMANENT_CODES, CHECK_ENGINE, READINESS, RECENT_CLEAR,
    COOLANT, VOLTAGE, FUEL_TRIMS, O2_SENSOR, IDLE,
}

/** Why an item got its status (typed; the UI and the report render it from resources, the AI in English). */
enum class HealthNote {
    NONE,
    /** Hot coolant with the engine off: heat soak after driving, not a fault. */
    RESIDUAL_HEAT,
    /** Engine below 80 °C: trims, O2 and idle are not judged. */
    COLD_ENGINE,
    /** Engine not running during the live part. */
    ENGINE_OFF,
    /** Battery voltage judged as engine-off (resting) voltage. */
    RESTING_VOLTAGE,
    /** Charging voltage judged with the engine running. */
    CHARGING_VOLTAGE,
    /** Mode 0A (permanent codes) exists on CAN cars only. */
    CAN_ONLY,
    /** Idle speed changes a lot within the sample. */
    UNSTABLE_IDLE,
    /** O2 sensor voltage barely moves. */
    O2_LAZY,
    /** Car driven or moving during the sample: idle not judged. */
    MOVING,
    /** Counters since clear are low: codes were cleared recently. */
    CLEARED_RECENTLY,
}

data class HealthItem(val id: HealthItemId, val status: HealthStatus, val value: Double? = null, val note: HealthNote = HealthNote.NONE)

data class HealthResult(val verdict: HealthStatus, val items: List<HealthItem>)

/**
 * What one quick check read. Code lists: null = the ECU could not be asked; [permanentSupported] = false on non-CAN
 * cars. [samples]: live values of ~15 s by sensor id ([SensorIds]; O2 B1S1 voltage = "01.14").
 */
data class HealthInput(
    val stored: List<String>?,
    val pending: List<String>?,
    val permanent: List<String>?,
    val permanentSupported: Boolean,
    val milOn: Boolean?,
    val supportedMonitors: Int?,
    val incompleteMonitors: Int?,
    /** null = no counters; true = counters since clear are below the used-car thresholds. */
    val lowClearCounters: Boolean?,
    val samples: Map<String, List<Double>>,
)

/** Deterministic quick health check (B6). Numbers come from [Thresholds]; context decides whether a value is judged. */
object HealthCheck {
    const val O2 = "01.14"

    fun evaluate(input: HealthInput): HealthResult {
        val s = input.samples
        val rpm = s[SensorIds.RPM].orEmpty()
        val running = rpm.isNotEmpty() && median(rpm) > Thresholds.RUNNING_RPM
        val coolantMax = s[SensorIds.COOLANT]?.maxOrNull()
        val warm = coolantMax != null && coolantMax >= Thresholds.WARM_COOLANT_C
        val moving = s[SensorIds.SPEED].orEmpty().any { it > 2.0 }
        val items = listOf(
            codes(HealthItemId.STORED_CODES, input.stored, HealthStatus.FAULT),
            codes(HealthItemId.PENDING_CODES, input.pending, HealthStatus.ATTENTION),
            if (!input.permanentSupported) HealthItem(HealthItemId.PERMANENT_CODES, HealthStatus.NOT_SUPPORTED, note = HealthNote.CAN_ONLY)
            else codes(HealthItemId.PERMANENT_CODES, input.permanent, HealthStatus.FAULT),
            when (input.milOn) {
                null -> HealthItem(HealthItemId.CHECK_ENGINE, HealthStatus.NOT_SUPPORTED)
                true -> HealthItem(HealthItemId.CHECK_ENGINE, HealthStatus.FAULT)
                false -> HealthItem(HealthItemId.CHECK_ENGINE, HealthStatus.OK)
            },
            readiness(input),
            when (input.lowClearCounters) {
                null -> HealthItem(HealthItemId.RECENT_CLEAR, HealthStatus.NOT_SUPPORTED)
                true -> HealthItem(HealthItemId.RECENT_CLEAR, HealthStatus.ATTENTION, note = HealthNote.CLEARED_RECENTLY)
                false -> HealthItem(HealthItemId.RECENT_CLEAR, HealthStatus.OK)
            },
            coolant(coolantMax, running),
            voltage(s[SensorIds.MODULE_VOLTAGE] ?: s[SensorIds.ELM_VOLTAGE], running),
            trims(s, running, warm),
            o2(s[O2], running, warm),
            idle(rpm, running, warm, moving),
        )
        return HealthResult(verdict(items), items)
    }

    fun verdict(items: List<HealthItem>): HealthStatus {
        val worst = items.maxOfOrNull { it.status.rank } ?: return HealthStatus.NOT_SUPPORTED
        return when {
            worst >= HealthStatus.OK.rank -> HealthStatus.entries.first { it.rank == worst }
            else -> HealthStatus.NOT_SUPPORTED
        }
    }

    private fun codes(id: HealthItemId, codes: List<String>?, bad: HealthStatus) = when {
        codes == null -> HealthItem(id, HealthStatus.NOT_SUPPORTED)
        codes.isEmpty() -> HealthItem(id, HealthStatus.OK, 0.0)
        else -> HealthItem(id, bad, codes.size.toDouble())
    }

    private fun readiness(input: HealthInput): HealthItem {
        val supported = input.supportedMonitors
        val incomplete = input.incompleteMonitors
        return when {
            supported == null || supported == 0 || incomplete == null -> HealthItem(HealthItemId.READINESS, HealthStatus.NOT_SUPPORTED)
            incomplete == 0 -> HealthItem(HealthItemId.READINESS, HealthStatus.OK, 0.0)
            else -> HealthItem(HealthItemId.READINESS, HealthStatus.ATTENTION, incomplete.toDouble())
        }
    }

    private fun coolant(max: Double?, running: Boolean): HealthItem = when {
        max == null -> HealthItem(HealthItemId.COOLANT, HealthStatus.NOT_SUPPORTED)
        !running && max >= Thresholds.OVERHEAT_WARN_C -> HealthItem(HealthItemId.COOLANT, HealthStatus.OK, max, HealthNote.RESIDUAL_HEAT)
        max >= Thresholds.OVERHEAT_CRIT_C -> HealthItem(HealthItemId.COOLANT, HealthStatus.FAULT, max)
        max >= Thresholds.OVERHEAT_WARN_C -> HealthItem(HealthItemId.COOLANT, HealthStatus.ATTENTION, max)
        running && max < Thresholds.WARM_COOLANT_C -> HealthItem(HealthItemId.COOLANT, HealthStatus.OK, max, HealthNote.COLD_ENGINE)
        else -> HealthItem(HealthItemId.COOLANT, HealthStatus.OK, max)
    }

    private fun voltage(v: List<Double>?, running: Boolean): HealthItem {
        if (v.isNullOrEmpty()) return HealthItem(HealthItemId.VOLTAGE, HealthStatus.NOT_SUPPORTED)
        val m = median(v)
        if (!running) {
            val status = when {
                m < Thresholds.HEALTH_BATTERY_FAULT_V -> HealthStatus.FAULT
                m < Thresholds.HEALTH_BATTERY_LOW_V -> HealthStatus.ATTENTION
                else -> HealthStatus.OK
            }
            return HealthItem(HealthItemId.VOLTAGE, status, m, HealthNote.RESTING_VOLTAGE)
        }
        val status = when {
            m < Thresholds.LIVE_NOT_CHARGING_V || m > Thresholds.LIVE_OVERCHARGE_V -> HealthStatus.FAULT
            m < Thresholds.VOLTAGE_LOW_V || m > Thresholds.VOLTAGE_HIGH_V -> HealthStatus.ATTENTION
            else -> HealthStatus.OK
        }
        return HealthItem(HealthItemId.VOLTAGE, status, m, HealthNote.CHARGING_VOLTAGE)
    }

    private fun trims(s: Map<String, List<Double>>, running: Boolean, warm: Boolean): HealthItem {
        val banks = listOf(SensorIds.STFT1 to SensorIds.LTFT1, SensorIds.STFT2 to SensorIds.LTFT2)
            .mapNotNull { (st, lt) -> s[lt]?.takeIf { it.isNotEmpty() }?.let { l -> (s[st]?.average() ?: 0.0) to l.average() } }
        if (banks.isEmpty()) return HealthItem(HealthItemId.FUEL_TRIMS, HealthStatus.NOT_SUPPORTED)
        if (!running) return HealthItem(HealthItemId.FUEL_TRIMS, HealthStatus.NOT_JUDGED, note = HealthNote.ENGINE_OFF)
        if (!warm) return HealthItem(HealthItemId.FUEL_TRIMS, HealthStatus.NOT_JUDGED, note = HealthNote.COLD_ENGINE)
        val worstTotal = banks.maxOf { abs(it.first + it.second) }
        val worstLong = banks.maxOf { abs(it.second) }
        val status = when {
            worstTotal > Thresholds.TOTAL_TRIM_CRIT_PCT -> HealthStatus.FAULT
            worstLong > Thresholds.LTFT_LIMIT_PCT -> HealthStatus.ATTENTION
            else -> HealthStatus.OK
        }
        val shown = banks.maxBy { abs(it.first + it.second) }.let { it.first + it.second }
        return HealthItem(HealthItemId.FUEL_TRIMS, status, shown)
    }

    private fun o2(v: List<Double>?, running: Boolean, warm: Boolean): HealthItem {
        if (v.isNullOrEmpty()) return HealthItem(HealthItemId.O2_SENSOR, HealthStatus.NOT_SUPPORTED)
        if (!running) return HealthItem(HealthItemId.O2_SENSOR, HealthStatus.NOT_JUDGED, note = HealthNote.ENGINE_OFF)
        if (!warm) return HealthItem(HealthItemId.O2_SENSOR, HealthStatus.NOT_JUDGED, note = HealthNote.COLD_ENGINE)
        val span = v.max() - v.min()
        return if (span < Thresholds.HEALTH_O2_MIN_SWING_V) HealthItem(HealthItemId.O2_SENSOR, HealthStatus.ATTENTION, span, HealthNote.O2_LAZY)
        else HealthItem(HealthItemId.O2_SENSOR, HealthStatus.OK, span)
    }

    private fun idle(rpm: List<Double>, running: Boolean, warm: Boolean, moving: Boolean): HealthItem {
        if (rpm.isEmpty()) return HealthItem(HealthItemId.IDLE, HealthStatus.NOT_SUPPORTED)
        if (!running) return HealthItem(HealthItemId.IDLE, HealthStatus.NOT_JUDGED, note = HealthNote.ENGINE_OFF)
        if (moving) return HealthItem(HealthItemId.IDLE, HealthStatus.NOT_JUDGED, note = HealthNote.MOVING)
        val m = median(rpm)
        if (!warm) return HealthItem(HealthItemId.IDLE, HealthStatus.NOT_JUDGED, m, HealthNote.COLD_ENGINE)
        val swing = rpm.max() - rpm.min()
        return when {
            m > Thresholds.IDLE_MAX_RPM || m < Thresholds.HEALTH_IDLE_MIN_RPM -> HealthItem(HealthItemId.IDLE, HealthStatus.ATTENTION, m)
            swing > Thresholds.HEALTH_IDLE_MAX_SWING_RPM -> HealthItem(HealthItemId.IDLE, HealthStatus.ATTENTION, m, HealthNote.UNSTABLE_IDLE)
            else -> HealthItem(HealthItemId.IDLE, HealthStatus.OK, m)
        }
    }

    private fun median(v: List<Double>): Double = v.sorted()[(v.size - 1) / 2]
}

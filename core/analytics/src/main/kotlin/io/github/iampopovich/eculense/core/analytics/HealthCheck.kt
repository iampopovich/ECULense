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
    /** R-H: diesel — petrol fuel-trim norms do not apply. */
    NOT_APPLICABLE_DIESEL,
    /** R-H: wideband upstream O2 sensor — the 0.1–0.9 V swing check does not apply. */
    WIDEBAND_O2,
}

/** [norm] = the per-car norm the value was compared with (R-H), shown as «compared with» and sent to the AI. */
data class HealthItem(val id: HealthItemId, val status: HealthStatus, val value: Double? = null, val note: HealthNote = HealthNote.NONE, val norm: Norm? = null)

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

/**
 * Deterministic quick health check (B6). Class-2 limits (coolant, battery, not charging / overcharge) come from
 * [Thresholds] and stay absolute; engine-specific items (charging window, trims, O2 swing, idle) compare with the car's
 * [CarNorms] — with only a GENERIC norm a FAULT is capped at ATTENTION. Context decides whether a value is judged.
 */
object HealthCheck {
    const val O2 = "01.14"

    /** FAULT from a generic norm is capped at ATTENTION (the generic number is not verified for this engine). */
    private fun cap(status: HealthStatus, norm: Norm?) =
        if (norm?.isGeneric == true && status == HealthStatus.FAULT) HealthStatus.ATTENTION else status

    fun evaluate(input: HealthInput, norms: CarNorms = CarNorms.EMPTY): HealthResult {
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
            voltage(s[SensorIds.MODULE_VOLTAGE] ?: s[SensorIds.ELM_VOLTAGE], running, moving, norms),
            trims(s, running, warm, norms),
            o2(s[O2], running, warm, norms),
            idle(rpm, running, warm, moving, norms),
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
        else -> HealthItem(HealthItemId.COOLANT, HealthStatus.OK, max)
    }

    private fun voltage(v: List<Double>?, running: Boolean, moving: Boolean, norms: CarNorms): HealthItem {
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
        val norm = norms.effective(if (moving) NormKey.CHARGING_DRIVING_V else NormKey.CHARGING_IDLE_V)
        val status = when {
            m < Thresholds.LIVE_NOT_CHARGING_V || m > Thresholds.LIVE_OVERCHARGE_V -> HealthStatus.FAULT
            norm?.outside(m) == true -> HealthStatus.ATTENTION
            else -> HealthStatus.OK
        }
        return HealthItem(HealthItemId.VOLTAGE, status, m, HealthNote.CHARGING_VOLTAGE, norm)
    }

    private fun trims(s: Map<String, List<Double>>, running: Boolean, warm: Boolean, norms: CarNorms): HealthItem {
        val banks = listOf(Triple(SensorIds.STFT1, SensorIds.LTFT1, NormKey.LTFT_B1), Triple(SensorIds.STFT2, SensorIds.LTFT2, NormKey.LTFT_B2))
            .mapNotNull { (st, lt, key) -> s[lt]?.takeIf { it.isNotEmpty() }?.let { l -> Triple(s[st]?.average() ?: 0.0, l.average(), key) } }
        if (banks.isEmpty()) return HealthItem(HealthItemId.FUEL_TRIMS, HealthStatus.NOT_SUPPORTED)
        if (norms.traits.isDiesel) return HealthItem(HealthItemId.FUEL_TRIMS, HealthStatus.NOT_SUPPORTED, note = HealthNote.NOT_APPLICABLE_DIESEL)
        if (!running) return HealthItem(HealthItemId.FUEL_TRIMS, HealthStatus.NOT_JUDGED, note = HealthNote.ENGINE_OFF)
        if (!warm) return HealthItem(HealthItemId.FUEL_TRIMS, HealthStatus.NOT_JUDGED, note = HealthNote.COLD_ENGINE)
        val totalNorm = norms.effective(NormKey.TOTAL_TRIM)
        val worst = banks.maxBy { abs(it.first + it.second) }
        val shown = worst.first + worst.second
        val longOut = banks.firstOrNull { b -> norms.effective(b.third)?.outside(b.second) == true }
        return when {
            totalNorm?.outside(shown) == true -> HealthItem(HealthItemId.FUEL_TRIMS, cap(HealthStatus.FAULT, totalNorm), shown, norm = totalNorm)
            longOut != null -> HealthItem(HealthItemId.FUEL_TRIMS, HealthStatus.ATTENTION, longOut.second, norm = norms.effective(longOut.third))
            else -> HealthItem(HealthItemId.FUEL_TRIMS, HealthStatus.OK, shown, norm = totalNorm)
        }
    }

    private fun o2(v: List<Double>?, running: Boolean, warm: Boolean, norms: CarNorms): HealthItem {
        if (norms.traits.isWideband) return HealthItem(HealthItemId.O2_SENSOR, HealthStatus.NOT_SUPPORTED, note = HealthNote.WIDEBAND_O2)
        if (v.isNullOrEmpty()) return HealthItem(HealthItemId.O2_SENSOR, HealthStatus.NOT_SUPPORTED)
        if (!running) return HealthItem(HealthItemId.O2_SENSOR, HealthStatus.NOT_JUDGED, note = HealthNote.ENGINE_OFF)
        if (!warm) return HealthItem(HealthItemId.O2_SENSOR, HealthStatus.NOT_JUDGED, note = HealthNote.COLD_ENGINE)
        val span = v.max() - v.min()
        val norm = norms.effective(NormKey.O2_SWING) ?: return HealthItem(HealthItemId.O2_SENSOR, HealthStatus.NOT_JUDGED, span)
        return if (norm.below(span)) HealthItem(HealthItemId.O2_SENSOR, HealthStatus.ATTENTION, span, HealthNote.O2_LAZY, norm)
        else HealthItem(HealthItemId.O2_SENSOR, HealthStatus.OK, span, norm = norm)
    }

    private fun idle(rpm: List<Double>, running: Boolean, warm: Boolean, moving: Boolean, norms: CarNorms): HealthItem {
        if (rpm.isEmpty()) return HealthItem(HealthItemId.IDLE, HealthStatus.NOT_SUPPORTED)
        if (!running) return HealthItem(HealthItemId.IDLE, HealthStatus.NOT_JUDGED, note = HealthNote.ENGINE_OFF)
        if (moving) return HealthItem(HealthItemId.IDLE, HealthStatus.NOT_JUDGED, note = HealthNote.MOVING)
        val m = median(rpm)
        if (!warm) return HealthItem(HealthItemId.IDLE, HealthStatus.NOT_JUDGED, m, HealthNote.COLD_ENGINE)
        val swing = rpm.max() - rpm.min()
        val norm = norms.effective(NormKey.IDLE_RPM)
        val swingNorm = norms.effective(NormKey.IDLE_RPM_SWING)
        return when {
            norm?.outside(m) == true -> HealthItem(HealthItemId.IDLE, HealthStatus.ATTENTION, m, norm = norm)
            swingNorm?.above(swing) == true -> HealthItem(HealthItemId.IDLE, HealthStatus.ATTENTION, m, HealthNote.UNSTABLE_IDLE, swingNorm)
            else -> HealthItem(HealthItemId.IDLE, HealthStatus.OK, m, norm = norm)
        }
    }

    private fun median(v: List<Double>): Double = v.sorted()[(v.size - 1) / 2]
}

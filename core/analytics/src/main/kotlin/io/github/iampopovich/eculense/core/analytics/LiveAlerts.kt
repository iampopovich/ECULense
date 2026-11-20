package io.github.iampopovich.eculense.core.analytics

/** C4: what can raise a live alert during a connection. */
enum class LiveAlertKind { OVERHEAT, NOT_CHARGING, OVERCHARGE }

/** One threshold as data: the user can switch it off or change the number in the alert settings. */
data class AlertThreshold(val enabled: Boolean, val value: Double)

data class LiveAlertConfig(
    val overheat: AlertThreshold = AlertThreshold(true, Thresholds.OVERHEAT_CRIT_C),
    val notCharging: AlertThreshold = AlertThreshold(true, Thresholds.LIVE_NOT_CHARGING_V),
    val overcharge: AlertThreshold = AlertThreshold(true, Thresholds.LIVE_OVERCHARGE_V),
    /** New trouble code during the trip (modes 03 and 07 every 60 s). */
    val newDtc: Boolean = true,
) {
    fun of(kind: LiveAlertKind): AlertThreshold = when (kind) {
        LiveAlertKind.OVERHEAT -> overheat
        LiveAlertKind.NOT_CHARGING -> notCharging
        LiveAlertKind.OVERCHARGE -> overcharge
    }

    fun with(kind: LiveAlertKind, t: AlertThreshold): LiveAlertConfig = when (kind) {
        LiveAlertKind.OVERHEAT -> copy(overheat = t)
        LiveAlertKind.NOT_CHARGING -> copy(notCharging = t)
        LiveAlertKind.OVERCHARGE -> copy(overcharge = t)
    }

    companion object {
        /** Ranges the settings screen accepts (outside them the number is a typo, not a threshold). */
        fun validRange(kind: LiveAlertKind): ClosedFloatingPointRange<Double> = when (kind) {
            LiveAlertKind.OVERHEAT -> 50.0..130.0
            LiveAlertKind.NOT_CHARGING -> 10.0..13.5
            LiveAlertKind.OVERCHARGE -> 14.0..17.0
        }
    }
}

/** An alert went up ([raised] = true, [notify] = post the heads-up notification) or cleared. */
data class LiveAlertChange(val kind: LiveAlertKind, val raised: Boolean, val value: Double, val notify: Boolean)

/**
 * Live threshold checks with confirmation time, hysteresis and a notification cooldown, fed with readings as they
 * arrive. Pure: time comes with every reading. Voltage = control module voltage (01.42) when the ECU reports it,
 * otherwise the adapter's ATRV (elm.voltage).
 */
class LiveAlertEvaluator(
    var config: LiveAlertConfig = LiveAlertConfig(),
    private val confirmMs: Long = Thresholds.LIVE_CONFIRM_MS,
    private val cooldownMs: Long = Thresholds.LIVE_NOTIFY_COOLDOWN_MS,
) {
    private val active = LinkedHashMap<LiveAlertKind, Double>()
    private val pendingSince = HashMap<LiveAlertKind, Long>()
    private val lastNotified = HashMap<LiveAlertKind, Long>()
    private var rpm: Double? = null
    private var rpmAt = Long.MIN_VALUE
    private var moduleVoltageSeen = false

    val activeKinds: Set<LiveAlertKind> get() = active.keys.toSet()
    fun valueOf(kind: LiveAlertKind): Double? = active[kind]

    fun onReading(sensorId: String, value: Double, atMs: Long): List<LiveAlertChange> {
        when (sensorId) {
            SensorIds.RPM -> {
                rpm = value; rpmAt = atMs
                // Engine back to idle/off: a not-charging alert has no meaning any more.
                if (value <= Thresholds.LIVE_CHARGING_RPM) return listOfNotNull(clear(LiveAlertKind.NOT_CHARGING, value))
                return emptyList()
            }
            SensorIds.COOLANT -> return listOfNotNull(check(LiveAlertKind.OVERHEAT, value, atMs, value >= config.overheat.value, value < config.overheat.value - Thresholds.LIVE_COOLANT_HYSTERESIS_C))
            SensorIds.MODULE_VOLTAGE, SensorIds.ELM_VOLTAGE -> {
                if (sensorId == SensorIds.MODULE_VOLTAGE) moduleVoltageSeen = true
                else if (moduleVoltageSeen) return emptyList()
                val running = rpm?.let { it > Thresholds.LIVE_CHARGING_RPM && atMs - rpmAt <= Thresholds.STATE_MAX_AGE_MS } == true
                val h = Thresholds.LIVE_VOLTAGE_HYSTERESIS_V
                return listOfNotNull(
                    check(LiveAlertKind.NOT_CHARGING, value, atMs, running && value < config.notCharging.value, !running || value >= config.notCharging.value + h),
                    check(LiveAlertKind.OVERCHARGE, value, atMs, value > config.overcharge.value, value < config.overcharge.value - h),
                )
            }
        }
        return emptyList()
    }

    /** The connection ended: everything clears (a new trip starts fresh, the cooldown is kept). */
    fun reset() {
        active.clear(); pendingSince.clear(); rpm = null; moduleVoltageSeen = false
    }

    private fun check(kind: LiveAlertKind, value: Double, atMs: Long, bad: Boolean, safe: Boolean): LiveAlertChange? {
        if (!config.of(kind).enabled) return clear(kind, value)
        if (kind in active) {
            if (safe) return clear(kind, value)
            active[kind] = value
            return null
        }
        if (!bad) {
            pendingSince.remove(kind)
            return null
        }
        val since = pendingSince.getOrPut(kind) { atMs }
        if (atMs - since < confirmMs) return null
        pendingSince.remove(kind)
        active[kind] = value
        val last = lastNotified[kind]
        val notify = last == null || atMs - last >= cooldownMs
        if (notify) lastNotified[kind] = atMs
        return LiveAlertChange(kind, raised = true, value = value, notify = notify)
    }

    private fun clear(kind: LiveAlertKind, value: Double): LiveAlertChange? {
        pendingSince.remove(kind)
        if (active.remove(kind) == null) return null
        return LiveAlertChange(kind, raised = false, value = value, notify = false)
    }
}

/**
 * C4: codes present when the connection started are the baseline and never alert; a code read later that was not
 * seen before in this connection is new (reported once).
 */
class NewDtcTracker {
    private var seen: MutableSet<String>? = null

    /** [codes] = stored + pending codes of one read; returns the new ones (empty on the first read). */
    fun onRead(codes: Collection<String>): List<String> {
        val known = seen
        if (known == null) {
            seen = codes.toMutableSet()
            return emptyList()
        }
        val fresh = codes.filter { it !in known }.distinct()
        known += fresh
        return fresh
    }

    fun reset() {
        seen = null
    }
}

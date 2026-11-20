package io.github.iampopovich.eculense.core.data

/**
 * C5 auto-record decisions, pure and time-driven (tests run on virtual time).
 * - Idle: speed > [startSpeedKmh] (or rpm > 0 when the car has no speed PID) continuously for [armMs] → [Action.START].
 * - Recording: the link goes down (engine off, disconnect, failed) → Holding.
 * - Holding: the ECU answers again within [holdMs] → back to Recording (the same recording continues);
 *   [holdMs] passes → [Action.STOP].
 * - A recording the user started or stopped by hand is left alone; after a manual stop of an auto recording nothing
 *   re-arms until the link goes down once (otherwise it would restart 5 s later while still driving).
 */
class AutoRecordMachine(
    private val startSpeedKmh: Double = START_SPEED_KMH,
    private val armMs: Long = ARM_MS,
    val holdMs: Long = HOLD_MS,
) {
    enum class Action { START, STOP }

    sealed interface State {
        data object Idle : State
        data class Arming(val sinceMs: Long) : State
        data object Recording : State
        data class Holding(val sinceMs: Long) : State
        /** A manual recording runs, or the user stopped an auto one: wait for the link to go down. */
        data object Suppressed : State
    }

    var state: State = State.Idle
        private set

    /** One live value. [hasSpeedPid] = the ECU reports vehicle speed (otherwise rpm > 0 arms). */
    fun onReading(sensorId: String, value: Double, atMs: Long, hasSpeedPid: Boolean): Action? {
        val s = state
        if (s !is State.Idle && s !is State.Arming) return null
        val moving = when {
            hasSpeedPid && sensorId == SPEED -> value > startSpeedKmh
            !hasSpeedPid && sensorId == RPM -> value > 0.0
            else -> return null
        }
        if (!moving) {
            state = State.Idle
            return null
        }
        val since = (s as? State.Arming)?.sinceMs ?: atMs.also { state = State.Arming(it) }
        if (atMs - since >= armMs) {
            state = State.Recording
            return Action.START
        }
        return null
    }

    /** Engine off, disconnect or a failed reconnect. */
    fun onLinkDown(atMs: Long) {
        state = when (state) {
            State.Recording -> State.Holding(atMs)
            is State.Holding -> state
            State.Suppressed, is State.Arming, State.Idle -> State.Idle
        }
    }

    /** The ECU answers (Connected). */
    fun onLinkUp() {
        if (state is State.Holding) state = State.Recording
    }

    /** Called when time passes; returns STOP once the hold time ran out. */
    fun onTick(atMs: Long): Action? {
        val s = state
        if (s is State.Holding && atMs - s.sinceMs >= holdMs) {
            state = State.Idle
            return Action.STOP
        }
        return null
    }

    /** The user started a recording by hand. */
    fun onManualStart() {
        if (state !is State.Recording && state !is State.Holding) state = State.Suppressed
    }

    /** The recording ended without the machine asking (user stop, or the recorder closed it). */
    fun onRecordingEnded() {
        state = when (state) {
            State.Recording, is State.Holding -> State.Suppressed
            else -> state
        }
    }

    companion object {
        const val START_SPEED_KMH = 10.0
        const val ARM_MS = 5_000L
        const val HOLD_MS = 3 * 60_000L
        private const val SPEED = "01.0D"
        private const val RPM = "01.0C"
    }
}

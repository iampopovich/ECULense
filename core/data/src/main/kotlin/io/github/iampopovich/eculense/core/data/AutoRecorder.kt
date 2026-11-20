package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.StandardPids
import io.github.iampopovich.eculense.core.obd.isSimulated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * C5 «record trips by itself»: drives [AutoRecordMachine] from the connection while the setting is on. Polls rpm and
 * speed itself (owner [OWNER]) so it works with the app in background. A simulated link (demo/replay) never starts a
 * recording ([LiveWriteGate]). Auto recordings go to the active car without asking (C8).
 */
class AutoRecorder internal constructor(
    private val controller: ConnectionController,
    private val recorder: SessionRecorder,
    private val prefs: AutomationPrefs,
    private val scope: CoroutineScope,
    private val sessionName: (stamp: String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val machine: AutoRecordMachine = AutoRecordMachine(),
) {
    private var holdJob: Job? = null
    private var startedByMe = false

    fun start() {
        scope.launch {
            prefs.settings.map { it.autoRecord }.distinctUntilChanged().collectLatest { on ->
                if (on) run() else controller.setPolledPids(OWNER, emptyList())
            }
        }
    }

    private suspend fun run() {
        kotlinx.coroutines.coroutineScope {
            launch {
                controller.state.collect { s ->
                    when {
                        s is ConnectionState.Connected && !s.isSimulated -> {
                            controller.setPolledPids(OWNER, listOfNotNull(StandardPids.byCode(RPM), StandardPids.byCode(SPEED)).filter { it.code in s.supportedPids })
                            machine.onLinkUp()
                            holdJob?.cancel()
                        }
                        s is ConnectionState.EngineOff || s is ConnectionState.Disconnected || s is ConnectionState.Failed -> {
                            if (s is ConnectionState.Disconnected || s is ConnectionState.Failed) controller.setPolledPids(OWNER, emptyList())
                            machine.onLinkDown(clock())
                            scheduleHold()
                        }
                        else -> Unit
                    }
                }
            }
            launch {
                controller.readings.collect { r ->
                    val s = controller.state.value as? ConnectionState.Connected ?: return@collect
                    if (s.isSimulated || recorder.activeSessionId.value != null && !startedByMe) return@collect
                    if (machine.onReading(r.sensorId, r.value, clock(), SPEED in s.supportedPids) == AutoRecordMachine.Action.START) {
                        if (recorder.activeSessionId.value == null) {
                            startedByMe = true
                            recorder.start(sessionName(stamp()), carId = null, auto = true)
                        }
                    }
                }
            }
            launch {
                // Manual start or stop seen on the recorder.
                recorder.activeSessionId.map { it != null }.distinctUntilChanged().drop(1).collect { active ->
                    if (active && !startedByMe) machine.onManualStart()
                    if (!active) {
                        if (startedByMe) machine.onRecordingEnded()
                        startedByMe = false
                    }
                }
            }
        }
    }

    private fun scheduleHold() {
        val holding = machine.state as? AutoRecordMachine.State.Holding ?: return
        if (holdJob?.isActive == true) return
        holdJob = scope.launch {
            delay((machine.holdMs - (clock() - holding.sinceMs)).coerceAtLeast(0))
            if (machine.onTick(clock()) == AutoRecordMachine.Action.STOP && startedByMe) {
                startedByMe = false
                recorder.stop()
            }
        }
    }

    private fun stamp(): String =
        DateTimeFormatter.ofPattern("dd.MM HH:mm").format(Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()))

    internal companion object {
        const val OWNER = "auto"
        private const val RPM = 0x0C
        private const val SPEED = 0x0D
    }
}

/**
 * C5: remembers the last real adapter that connected and, when «connect automatically» is on, connects to it once per
 * app start. [onAppStart] is called from the activity (a visible app may start the connectedDevice foreground service;
 * a background start is forbidden on Android 12+).
 */
class AutoConnect internal constructor(
    private val controller: ConnectionController,
    private val prefs: AutomationPrefs,
    private val scope: CoroutineScope,
) {
    private var tried = false

    fun start() {
        scope.launch {
            controller.state.collect { s ->
                if (s is ConnectionState.Connected && !s.isSimulated && prefs.settings.first().rememberLast) {
                    if (prefs.lastAdapter.first() != s.endpoint) prefs.setLastAdapter(s.endpoint)
                }
            }
        }
    }

    fun onAppStart() {
        if (tried) return
        tried = true
        scope.launch {
            val settings = prefs.settings.first()
            if (!settings.autoConnect || !settings.rememberLast) return@launch
            val last = prefs.lastAdapter.first() ?: return@launch
            if (controller.state.value is ConnectionState.Disconnected) controller.connect(last)
        }
    }

    /**
     * C9: the widget's «Connect» (the activity is in front, so the foreground service may start). Uses the last
     * adapter even when auto-connect is off — the user asked for it. false = no adapter remembered yet.
     */
    suspend fun connectLast(): Boolean {
        val last = prefs.lastAdapter.first() ?: return false
        if (controller.state.value is ConnectionState.Disconnected || controller.state.value is ConnectionState.Failed) controller.connect(last)
        return true
    }
}

package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.analytics.SensorIds
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.SessionSource
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.Pid
import io.github.iampopovich.eculense.core.obd.StandardPids
import io.github.iampopovich.eculense.core.obd.isSessionAlive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Writes to the DB whatever is currently being polled (like Car Scanner: "what is on screen gets recorded"). */
interface SessionRecorder {
    val activeSessionId: StateFlow<Long?>

    /** Whether the running recording was started by [AutoRecorder] (not by the user). */
    val activeIsAuto: StateFlow<Boolean> get() = NOT_AUTO

    fun start(name: String) = start(name, carId = null, auto = false)

    /**
     * [carId] = null → the active car (C8). [auto] = started by [AutoRecorder]: engine off and disconnect do not end
     * it (the auto recorder stops it after its hold time, so a short stop continues the same recording).
     */
    fun start(name: String, carId: Long?, auto: Boolean)
    fun stop()
}

private val NOT_AUTO: StateFlow<Boolean> = MutableStateFlow(false)

internal class LiveSessionRecorder(
    private val controller: ConnectionController,
    private val repository: SessionRepository,
    private val scope: CoroutineScope,
    /** After finish: trip for the recording and background analysis (3.9). Errors here do not break the recording. */
    private val onFinished: suspend (sessionId: Long) -> Unit = {},
) : SessionRecorder {
    private val _active = MutableStateFlow<Long?>(null)
    override val activeSessionId: StateFlow<Long?> = _active.asStateFlow()
    private val _auto = MutableStateFlow(false)
    override val activeIsAuto: StateFlow<Boolean> = _auto.asStateFlow()
    private var job: Job? = null

    override fun start(name: String, carId: Long?, auto: Boolean) {
        if (job?.isActive == true) return
        _auto.value = auto
        job = scope.launch {
            val id = repository.start(name, SessionSource.LIVE, vin = null, carId = carId)
            _active.value = id
            val batch = mutableListOf<SensorReading>()
            try {
                // The recording survives reconnects (readings resume in the same session); a stopped engine (A4),
                // a reconnect that gave up (Failed) and a user disconnect end it cleanly instead.
                coroutineScope {
                    val collector = launch {
                        controller.readings.collect { reading ->
                            batch += reading
                            if (batch.size >= BATCH_SIZE) {
                                repository.append(id, batch.toList())
                                batch.clear()
                            }
                        }
                    }
                    // Analytics needs coolant, trims, voltage... even if the dashboard does not show them (C1).
                    val demand = launch {
                        controller.state.collect { s ->
                            if (s is ConnectionState.Connected) controller.setPolledPids(OWNER, requiredPids(s.supportedPids))
                        }
                    }
                    if (auto) awaitCancellation()
                    controller.state.dropWhile { !it.isSessionAlive }.first { it.endsRecording() }
                    collector.cancel()
                    demand.cancel()
                }
            } finally {
                withContext(NonCancellable) {
                    controller.setPolledPids(OWNER, emptyList())
                    if (batch.isNotEmpty()) repository.append(id, batch.toList())
                    repository.finish(id)
                    _active.value = null
                    _auto.value = false
                    runCatching { onFinished(id) }
                }
                _active.value = null
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val BATCH_SIZE = 25
        const val OWNER = "recorder"
    }
}

/** Reconnecting keeps the recording open; these states close it. */
internal fun ConnectionState.endsRecording(): Boolean =
    this is ConnectionState.EngineOff || this is ConnectionState.Failed || this is ConnectionState.Disconnected

/**
 * PIDs a recording always polls (C1/C2): what the analytics rules and the trip calculator read, so a dashboard
 * without coolant or trims still yields complete recordings. They are read in their own (mostly slow) tier.
 */
internal fun requiredPids(supported: Set<Int>): List<Pid> =
    (SensorIds.FOR_RULES + SensorIds.FUEL_RATE)
        .mapNotNull { id -> StandardPids.all.firstOrNull { it.sensorId == id } }
        .filter { it.code in supported }
        .sortedBy { it.code }

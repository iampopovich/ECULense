package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.SessionSource
import io.github.iampopovich.eculense.core.obd.ConnectionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Writes to the DB whatever is currently being polled (like Car Scanner: "what is on screen gets recorded"). */
interface SessionRecorder {
    val activeSessionId: StateFlow<Long?>
    fun start(name: String)
    fun stop()
}

internal class LiveSessionRecorder(
    private val controller: ConnectionController,
    private val repository: SessionRepository,
    private val scope: CoroutineScope,
    /** After finish: trip for the recording and background analysis (3.9). Errors here do not break the recording. */
    private val onFinished: suspend (sessionId: Long) -> Unit = {},
) : SessionRecorder {
    private val _active = MutableStateFlow<Long?>(null)
    override val activeSessionId: StateFlow<Long?> = _active.asStateFlow()
    private var job: Job? = null

    override fun start(name: String) {
        if (job?.isActive == true) return
        job = scope.launch {
            val id = repository.start(name, SessionSource.LIVE, vin = null)
            _active.value = id
            val batch = mutableListOf<SensorReading>()
            try {
                controller.readings.collect { reading ->
                    batch += reading
                    if (batch.size >= BATCH_SIZE) {
                        repository.append(id, batch.toList())
                        batch.clear()
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    if (batch.isNotEmpty()) repository.append(id, batch.toList())
                    repository.finish(id)
                    _active.value = null
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
    }
}

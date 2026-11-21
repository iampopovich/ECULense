package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.model.AppLogger
import io.github.iampopovich.eculense.core.model.attempt
import io.github.iampopovich.eculense.core.analytics.StallDetector
import io.github.iampopovich.eculense.core.analytics.TripEventFact
import io.github.iampopovich.eculense.core.analytics.TripEventType
import io.github.iampopovich.eculense.core.database.EventDao
import io.github.iampopovich.eculense.core.database.TripEventEntity
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** B10: one stored event of a recording. */
data class TripEvent(val id: Long, val sessionId: Long, val atMs: Long, val type: TripEventType)

interface TripEventRepository {
    fun observe(sessionId: Long): Flow<List<TripEvent>>
    suspend fun forSession(sessionId: Long): List<TripEvent>
    suspend fun forCar(carId: Long, fromMs: Long = 0, toMs: Long = Long.MAX_VALUE): List<TripEvent>
}

/** What a tap on «Mark event» did. */
enum class MarkResult { SAVED, NOT_RECORDING, SIMULATED }

/** The dashboard's one-tap mark (B10). */
fun interface EventMarker {
    suspend fun mark(): MarkResult
}

internal class RoomTripEventRepository(private val dao: EventDao) : TripEventRepository {
    override fun observe(sessionId: Long): Flow<List<TripEvent>> = dao.observeForSession(sessionId).map { l -> l.mapNotNull { it.toModel() } }
    override suspend fun forSession(sessionId: Long): List<TripEvent> = dao.forSession(sessionId).mapNotNull { it.toModel() }
    override suspend fun forCar(carId: Long, fromMs: Long, toMs: Long): List<TripEvent> = dao.forCar(carId, fromMs, toMs).mapNotNull { it.toModel() }
}

internal fun TripEventEntity.toModel(): TripEvent? =
    TripEventType.entries.firstOrNull { it.name == type }?.let { TripEvent(id, sessionId, atMs, it) }

internal fun TripEvent.toFact() = TripEventFact(sessionId, atMs, type)

/**
 * B10: while a recording runs, watches rpm/speed/voltage for engine stalls ([StallDetector]) and stores them with the
 * recording; [mark] stores a user mark. Nothing is stored for a simulated link ([LiveWriteGate]).
 */
internal class LiveEventRecorder(
    private val controller: ConnectionController,
    private val recorder: SessionRecorder,
    private val dao: EventDao,
    private val gate: LiveWriteGate,
    private val scope: CoroutineScope,
    private val carOf: suspend (sessionId: Long) -> Long?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: AppLogger = AppLogger.NONE,
) : EventMarker {
    private val _stalls = MutableSharedFlow<Long>(extraBufferCapacity = 4)
    /** Times of stalls detected live (also for a simulated link, so the screen can show them). */
    val stalls: SharedFlow<Long> = _stalls.asSharedFlow()

    fun start() {
        scope.launch {
            recorder.activeSessionId.collectLatest { id ->
                if (id == null) return@collectLatest
                val detector = StallDetector()
                kotlinx.coroutines.coroutineScope {
                    launch {
                        controller.state.collect { s -> if (s !is ConnectionState.Connected) detector.reset() }
                    }
                    controller.readings.collect { r ->
                        if (controller.state.value !is ConnectionState.Connected) return@collect
                        if (detector.onReading(r.sensorId, r.value, r.timestampMs)) {
                            _stalls.tryEmit(r.timestampMs)
                            save(id, r.timestampMs, TripEventType.STALL)
                        }
                    }
                }
            }
        }
    }

    override suspend fun mark(): MarkResult {
        val id = recorder.activeSessionId.value ?: return MarkResult.NOT_RECORDING
        if (!gate.allowed()) return MarkResult.SIMULATED
        dao.insert(TripEventEntity(sessionId = id, carId = carOf(id), atMs = clock(), type = TripEventType.USER_MARK.name))
        return MarkResult.SAVED
    }

    private suspend fun save(sessionId: Long, atMs: Long, type: TripEventType) {
        if (!gate.allowed()) return
        logger.attempt("EcuEvents", "trip event ${type.name} for recording $sessionId") {
            dao.insert(TripEventEntity(sessionId = sessionId, carId = carOf(sessionId), atMs = atMs, type = type.name))
        }
    }
}

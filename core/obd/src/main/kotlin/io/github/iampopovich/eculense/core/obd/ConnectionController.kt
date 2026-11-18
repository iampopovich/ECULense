package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.SensorReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val endpoint: AdapterEndpoint) : ConnectionState
    data class Connected(val endpoint: AdapterEndpoint, val info: AdapterInfo, val supportedPids: Set<Int>) : ConnectionState
    data class Failed(val endpoint: AdapterEndpoint, val reason: String) : ConnectionState
}

interface ConnectionController {
    val state: StateFlow<ConnectionState>

    /** Значения PID из setPolledPids. Опрос идёт, только пока есть подписчики (экран или запись). */
    val readings: SharedFlow<SensorReading>

    suspend fun discover(): List<AdapterEndpoint>
    fun connect(endpoint: AdapterEndpoint)
    fun disconnect()
    fun setPolledPids(pids: List<Pid>)

    /** Разовые операции (ошибки, VIN). null — если не подключены. */
    suspend fun <T> withSession(block: suspend (ObdSession) -> T): T?
}

@OptIn(ExperimentalCoroutinesApi::class)
class ObdConnectionManager internal constructor(
    private val factories: List<TransportFactory>,
    private val scope: CoroutineScope,
    private val sessionFactory: (ObdTransport) -> ObdSession,
) : ConnectionController {

    constructor(factories: List<TransportFactory>, scope: CoroutineScope) :
        this(factories, scope, { transport -> Elm327Session(ElmLink(transport)) })

    private class Active(val transport: ObdTransport, val session: ObdSession)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private val polledPids = MutableStateFlow<List<Pid>>(emptyList())

    @Volatile
    private var active: Active? = null
    private var connectJob: Job? = null

    override val readings: SharedFlow<SensorReading> =
        combine(state, polledPids) { s, pids -> s to pids }
            .flatMapLatest { (s, pids) ->
                val current = active
                if (s is ConnectionState.Connected && current != null && pids.isNotEmpty()) {
                    LivePoller(current.session).poll(pids).catch { e -> onLinkLost(s.endpoint, e) }
                } else {
                    emptyFlow()
                }
            }
            .shareIn(scope, SharingStarted.WhileSubscribed(5_000))

    override suspend fun discover(): List<AdapterEndpoint> =
        factories.flatMap { factory -> runCatching { factory.discover() }.getOrDefault(emptyList()) }

    override fun connect(endpoint: AdapterEndpoint) {
        connectJob?.cancel()
        connectJob = scope.launch {
            closeActive()
            _state.value = ConnectionState.Connecting(endpoint)
            try {
                val factory = factories.firstOrNull { it.kind == endpoint.kind }
                    ?: throw ObdException("Нет транспорта '${endpoint.kind}'")
                val transport = factory.create(endpoint)
                transport.open()
                val session = sessionFactory(transport)
                active = Active(transport, session)
                val info = session.initialize()
                val pids = session.supportedPids()
                _state.value = ConnectionState.Connected(endpoint, info, pids)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeActive()
                _state.value = ConnectionState.Failed(endpoint, e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    override fun disconnect() {
        connectJob?.cancel()
        scope.launch {
            _state.value = ConnectionState.Disconnected
            closeActive()
        }
    }

    override fun setPolledPids(pids: List<Pid>) {
        polledPids.value = pids
    }

    override suspend fun <T> withSession(block: suspend (ObdSession) -> T): T? {
        val current = active ?: return null
        if (state.value !is ConnectionState.Connected) return null
        return block(current.session)
    }

    private fun onLinkLost(endpoint: AdapterEndpoint, cause: Throwable) {
        scope.launch {
            closeActive()
            _state.value = ConnectionState.Failed(endpoint, "Связь с адаптером потеряна: ${cause.message}")
        }
    }

    private suspend fun closeActive() {
        val current = active ?: return
        active = null
        runCatching { current.transport.close() }
    }
}

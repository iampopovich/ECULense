package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.SensorReading
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class FakeConnectionController(
    var endpoints: List<AdapterEndpoint> = emptyList(),
    initial: ConnectionState = ConnectionState.Disconnected,
    var session: ObdSession? = null,
) : ConnectionController {
    val stateFlow = MutableStateFlow(initial)
    override val state: StateFlow<ConnectionState> = stateFlow
    val readingsFlow = MutableSharedFlow<SensorReading>(extraBufferCapacity = 64)
    override val readings: SharedFlow<SensorReading> = readingsFlow
    val polled = MutableStateFlow<List<Pid>>(emptyList())
    val connectCalls = mutableListOf<AdapterEndpoint>()

    override suspend fun discover() = endpoints
    override fun connect(endpoint: AdapterEndpoint) {
        connectCalls += endpoint
    }
    override fun disconnect() {
        stateFlow.value = ConnectionState.Disconnected
    }
    val polledBy = MutableStateFlow<Map<String, List<Pid>>>(emptyMap())
    val background = MutableStateFlow(false)
    override val disabledPids = MutableStateFlow<Set<Int>>(emptySet())
    override val pollStats = MutableStateFlow<PollStats?>(null)

    override fun setPolledPids(pids: List<Pid>) = setPolledPids(DEFAULT_OWNER, pids)
    override fun setPolledPids(owner: String, pids: List<Pid>) {
        polledBy.value = if (pids.isEmpty()) polledBy.value - owner else LinkedHashMap(polledBy.value).apply { put(owner, pids) }
        polled.value = mergePolled(polledBy.value)
    }
    override fun setBackground(background: Boolean) {
        this.background.value = background
    }
    override suspend fun <T> withSession(block: suspend (ObdSession) -> T): T? = session?.let { block(it) }

    companion object {
        fun connected(supported: Set<Int>) = ConnectionState.Connected(
            AdapterEndpoint("fake", "00:11", "Fake ELM"),
            AdapterInfo("ELM327 v1.5", '6', true, 12.6),
            supported,
        )
    }
}

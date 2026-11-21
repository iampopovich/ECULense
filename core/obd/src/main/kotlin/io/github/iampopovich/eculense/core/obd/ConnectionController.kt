package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.SensorReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val endpoint: AdapterEndpoint) : ConnectionState
    data class Connected(
        val endpoint: AdapterEndpoint,
        val info: AdapterInfo,
        val supportedPids: Set<Int>,
        val capabilities: AdapterCapabilities = AdapterCapabilities.NONE,
    ) : ConnectionState

    /** The link dropped; attempt [attempt] to reopen it starts in [nextInMs] ms. [cause] = why the link dropped. */
    data class Reconnecting(val endpoint: AdapterEndpoint, val attempt: Int, val nextInMs: Long, val cause: ObdError) : ConnectionState

    /**
     * The adapter is alive (answers ATRV, [voltage] V) but the ECU stopped answering: the engine was switched off.
     * The session stays open and checks gently until the ECU answers again.
     */
    data class EngineOff(val endpoint: AdapterEndpoint, val voltage: Double?) : ConnectionState

    data class Failed(val endpoint: AdapterEndpoint, val error: ObdError) : ConnectionState
}

/** A session exists (connected, reconnecting or waiting for the engine): the foreground service stays on. */
val ConnectionState.isSessionAlive: Boolean
    get() = this is ConnectionState.Connected || this is ConnectionState.Reconnecting || this is ConnectionState.EngineOff

/** The endpoint of any state except Disconnected. */
val ConnectionState.endpointOrNull: AdapterEndpoint?
    get() = when (this) {
        ConnectionState.Disconnected -> null
        is ConnectionState.Connecting -> endpoint
        is ConnectionState.Connected -> endpoint
        is ConnectionState.Reconnecting -> endpoint
        is ConnectionState.EngineOff -> endpoint
        is ConnectionState.Failed -> endpoint
    }

/** The current link has no real car behind it ([AdapterEndpoint.isSimulated]). */
val ConnectionState.isSimulated: Boolean get() = endpointOrNull?.isSimulated == true

interface ConnectionController {
    val state: StateFlow<ConnectionState>

    /** PID values from setPolledPids. Polling runs only while there are subscribers (a screen or a recording). */
    val readings: SharedFlow<SensorReading>

    /** PIDs switched off for this connection because the ECU kept giving no value (A9). */
    val disabledPids: StateFlow<Set<Int>> get() = NO_PIDS

    /** Polling speed of the current connection (A7); null before the first cycle. */
    val pollStats: StateFlow<PollStats?> get() = NO_STATS

    suspend fun discover(): List<AdapterEndpoint>
    fun connect(endpoint: AdapterEndpoint)
    fun disconnect()
    fun setPolledPids(pids: List<Pid>)

    /**
     * Several consumers ask for PIDs at once (dashboard, car data screen, recorder): the poller reads the union, in
     * the order the owners first appeared. An empty list removes the owner. [setPolledPids] without an owner is the
     * [DEFAULT_OWNER] (the dashboard).
     */
    fun setPolledPids(owner: String, pids: List<Pid>) {
        if (owner == DEFAULT_OWNER) setPolledPids(pids)
    }

    /** C2: the app is in background / the screen is off; slower tiers are read half as often. */
    fun setBackground(background: Boolean) {}

    /** One-off operations (DTCs, VIN). null — if not connected. */
    suspend fun <T> withSession(block: suspend (ObdSession) -> T): T?

    /**
     * R-D: like [withSession], but live polling is paused while [block] runs (a module scan switches headers on) and
     * resumes afterwards. null — if not connected.
     */
    suspend fun <T> withExclusiveSession(block: suspend (ObdSession) -> T): T? = withSession(block)
}

const val DEFAULT_OWNER = "default"

/** Union of the PIDs requested by all owners, without duplicates, in owner order. */
fun mergePolled(byOwner: Map<String, List<Pid>>): List<Pid> =
    byOwner.values.flatten().distinctBy { it.code }

private val NO_PIDS: StateFlow<Set<Int>> = MutableStateFlow(emptySet())
private val NO_STATS: StateFlow<PollStats?> = MutableStateFlow(null)

/**
 * Connection lifecycle:
 * - a single command timeout never ends the session (the poller skips the PID);
 * - [LivePoller] stalls (no PID answered in 3 cycles) → ATRV: adapter silent → re-init on the same transport, then
 *   reopen; adapter alive but ECU silent → [ConnectionState.EngineOff] with a gentle check every [engineOffCheckMs];
 * - IOException (link lost) → reopen the transport with backoff 1, 2, 4, 8, 16, 30 s, giving up after [giveUpMs] of
 *   waiting or when the user disconnects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObdConnectionManager internal constructor(
    private val factories: List<TransportFactory>,
    private val scope: CoroutineScope,
    private val sessionFactory: (ObdTransport, SessionSetup) -> ObdSession,
    private val traffic: ObdTrafficLog? = null,
    private val prefs: AdapterPrefs = InMemoryAdapterPrefs(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val engineOffCheckMs: Long = 5_000,
    private val giveUpMs: Long = 120_000,
) : ConnectionController {

    constructor(
        factories: List<TransportFactory>,
        scope: CoroutineScope,
        trafficLog: ObdTrafficLog? = null,
        prefs: AdapterPrefs = InMemoryAdapterPrefs(),
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        factories,
        scope,
        { transport, setup -> Elm327Session(ElmLink(transport, trafficLog, clock = clock), clock, setup) },
        trafficLog,
        prefs,
        clock,
    )

    private class Active(val transport: ObdTransport, val session: ObdSession)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private val polledByOwner = MutableStateFlow<Map<String, List<Pid>>>(emptyMap())
    private val polledPids = MutableStateFlow<List<Pid>>(emptyList())
    private val background = MutableStateFlow(false)
    private val pollEpoch = MutableStateFlow(0)

    /** R-D: number of running exclusive operations (module scans); polling stops while > 0. */
    private val exclusive = MutableStateFlow(0)
    private val disabled = MutableStateFlow<Set<Int>>(emptySet())
    override val disabledPids: StateFlow<Set<Int>> = disabled.asStateFlow()
    private val stats = MutableStateFlow<PollStats?>(null)
    override val pollStats: StateFlow<PollStats?> = stats.asStateFlow()

    @Volatile
    private var active: Active? = null
    private var connectJob: Job? = null
    private var keepAliveJob: Job? = null

    override val readings: SharedFlow<SensorReading> =
        combine(state, polledPids, pollEpoch, exclusive) { s, pids, _, paused -> Triple(s, pids, paused > 0) }
            .flatMapLatest { (s, pids, paused) ->
                val current = active
                if (!paused && s is ConnectionState.Connected && current != null && pids.isNotEmpty()) {
                    LivePoller(current.session, clock, disabled, stats, background = background).poll(pids)
                        .catch { e -> onPollingFailure(s, current, e) }
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
            traffic?.note("connect: ${endpoint.kind}")
            try {
                openSession(endpoint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeActive()
                traffic?.note("connect failed: ${ObdError.of(e).technicalMessage}")
                _state.value = ConnectionState.Failed(endpoint, ObdError.of(e))
            }
        }
    }

    override fun disconnect() {
        connectJob?.cancel()
        scope.launch {
            if (_state.value !is ConnectionState.Disconnected) traffic?.note("disconnect")
            _state.value = ConnectionState.Disconnected
            closeActive()
        }
    }

    override fun setPolledPids(pids: List<Pid>) = setPolledPids(DEFAULT_OWNER, pids)

    override fun setPolledPids(owner: String, pids: List<Pid>) {
        val updated = polledByOwner.updateAndGet { current ->
            if (pids.isEmpty()) current - owner else LinkedHashMap(current).apply { put(owner, pids) }
        }
        // Restart the poller only when the union really changed.
        val merged = mergePolled(updated)
        if (merged != polledPids.value) polledPids.value = merged
    }

    override fun setBackground(background: Boolean) {
        this.background.value = background
    }

    override suspend fun <T> withSession(block: suspend (ObdSession) -> T): T? {
        val current = active ?: return null
        if (state.value !is ConnectionState.Connected) return null
        return block(current.session)
    }

    override suspend fun <T> withExclusiveSession(block: suspend (ObdSession) -> T): T? {
        val current = active ?: return null
        if (state.value !is ConnectionState.Connected) return null
        exclusive.update { it + 1 }
        try {
            traffic?.note("live polling paused for an exclusive operation")
            return block(current.session)
        } finally {
            exclusive.update { it - 1 }
            traffic?.note("live polling resumes")
        }
    }

    /** Opens the transport, initializes the adapter and publishes Connected. Throws on failure. */
    private suspend fun openSession(endpoint: AdapterEndpoint) {
        val factory = factories.firstOrNull { it.kind == endpoint.kind }
            ?: throw ObdException(ObdError.NoTransport(endpoint.kind))
        val key = adapterKey(endpoint)
        // A replayed log must meet the same commands it recorded: no user settings and no learned profile for it.
        val useSettings = endpoint.kind != ReplayTransportFactory.KIND
        // Simulated links (demo kinds, replay, PC emulator) never store a learned profile: the profile store is keyed
        // by the active car, and nothing simulated may persist into it (round 1 lesson, LiveWriteGate).
        val learn = !endpoint.isSimulated
        val setup = SessionSetup(
            settings = runCatching { prefs.settings.first() }.getOrDefault(AdapterSettings()).takeIf { useSettings } ?: AdapterSettings(),
            profile = (if (learn) runCatching { prefs.profile(key) }.getOrNull() else null) ?: AdapterProfile(),
            onProfile = { if (learn) prefs.saveProfile(key, it) },
        )
        val transport = factory.create(endpoint)
        transport.open()
        val session = sessionFactory(transport, setup)
        val current = Active(transport, session)
        active = current
        val info = session.initialize()
        val pids = session.supportedPids()
        disabled.value = emptySet()
        stats.value = null
        _state.value = ConnectionState.Connected(endpoint, info, pids, session.capabilities)
        startKeepAlive(current)
    }

    /** K-Line keepalive (A10): the session decides whether the bus needs it (never on CAN). */
    private fun startKeepAlive(current: Active) {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive && active === current) {
                delay(KEEPALIVE_TICK_MS)
                if (_state.value is ConnectionState.Connected) {
                    try {
                        current.session.keepAlive(KEEPALIVE_IDLE_MS)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        traffic?.note("keepalive failed: ${ObdError.of(e).technicalMessage}")
                    }
                }
            }
        }
    }

    private fun onPollingFailure(connected: ConnectionState.Connected, current: Active, cause: Throwable) {
        if (cause is CancellationException) throw cause
        if (active !== current) return
        connectJob?.cancel()
        connectJob = scope.launch {
            if (cause is EcuStalledException) {
                traffic?.note("no PID answered in several cycles: checking the adapter")
                handleStall(connected, current, cause.noDataPids)
            } else {
                traffic?.note("link lost: ${ObdError.of(cause).technicalMessage}")
                reconnect(connected.endpoint, ObdError.of(cause))
            }
        }
    }

    private suspend fun handleStall(connected: ConnectionState.Connected, current: Active, noData: Set<Int>) {
        val endpoint = connected.endpoint
        try {
            val voltage = current.session.batteryVoltage()
            when {
                voltage == null -> {
                    // The adapter itself does not answer: re-init on the same transport first.
                    traffic?.note("adapter silent: re-init on the same transport")
                    try {
                        val info = current.session.initialize()
                        val pids = current.session.supportedPids()
                        _state.value = ConnectionState.Connected(endpoint, info, pids, current.session.capabilities)
                        pollEpoch.update { it + 1 }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reconnect(endpoint, ObdError.of(e))
                    }
                }
                current.session.ecuResponds() -> {
                    // The ECU is fine; the polled PIDs are the problem.
                    disabled.update { it + noData }
                    pollEpoch.update { it + 1 }
                }
                else -> waitForEngine(connected, current, voltage)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IOException (link lost) and adapter errors alike (ObdException: BUS INIT ERROR, parser failures from
            // ATRV/0100, also while waiting for the engine): reopen the link. Nothing may escape into the app scope.
            traffic?.note("stall check failed: ${ObdError.of(e).technicalMessage}")
            reconnect(endpoint, ObdError.of(e))
        }
    }

    /** A4: engine off. ATRV every [engineOffCheckMs]; the ECU is asked when the voltage rises or every third check. */
    private suspend fun waitForEngine(connected: ConnectionState.Connected, current: Active, firstVoltage: Double) {
        traffic?.note("ECU silent, adapter at $firstVoltage V: engine off")
        _state.value = ConnectionState.EngineOff(connected.endpoint, firstVoltage)
        var checks = 0
        while (active === current) {
            delay(engineOffCheckMs)
            val voltage = current.session.batteryVoltage()
            _state.value = ConnectionState.EngineOff(connected.endpoint, voltage)
            checks++
            val charging = voltage != null && voltage >= Elm327Session.IGNITION_ON_V
            if ((charging || checks % 3 == 0) && current.session.ecuResponds()) {
                traffic?.note("ECU answers again: polling resumes")
                _state.value = connected
                pollEpoch.update { it + 1 }
                return
            }
        }
    }

    /** Reopens the transport with exponential backoff until it works, [giveUpMs] of waiting pass, or disconnect. */
    private suspend fun reconnect(endpoint: AdapterEndpoint, firstCause: ObdError) {
        closeActive()
        var cause = firstCause
        var waited = 0L
        var attempt = 0
        while (true) {
            attempt++
            val wait = backoffMs(attempt)
            if (waited + wait > giveUpMs) {
                traffic?.note("reconnect: giving up after $attempt attempts")
                _state.value = ConnectionState.Failed(endpoint, ObdError.LinkLost(cause))
                return
            }
            _state.value = ConnectionState.Reconnecting(endpoint, attempt, wait, cause)
            delay(wait)
            waited += wait
            try {
                traffic?.note("reconnect attempt $attempt")
                openSession(endpoint)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cause = ObdError.of(e)
                closeActive()
            }
        }
    }

    private suspend fun closeActive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        val current = active ?: return
        active = null
        runCatching { current.transport.close() }
    }

    internal companion object {
        const val KEEPALIVE_TICK_MS = 1_000L
        const val KEEPALIVE_IDLE_MS = 4_000L

        /** 1, 2, 4, 8, 16 s, then 30 s. */
        fun backoffMs(attempt: Int): Long = if (attempt >= 6) 30_000 else 1_000L shl (attempt - 1)

        fun adapterKey(endpoint: AdapterEndpoint) = "${endpoint.kind}|${endpoint.address}"
    }
}

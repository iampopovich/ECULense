package io.github.iampopovich.eculense.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import io.github.iampopovich.eculense.core.analytics.AlertThreshold
import io.github.iampopovich.eculense.core.analytics.LiveAlertChange
import io.github.iampopovich.eculense.core.analytics.LiveAlertConfig
import io.github.iampopovich.eculense.core.analytics.LiveAlertEvaluator
import io.github.iampopovich.eculense.core.analytics.LiveAlertKind
import io.github.iampopovich.eculense.core.analytics.NewDtcTracker
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.StandardPids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** C4: alert thresholds the user can edit (DataStore «alerts»). */
interface AlertPrefs {
    val config: Flow<LiveAlertConfig>
    suspend fun update(transform: (LiveAlertConfig) -> LiveAlertConfig)
}

class InMemoryAlertPrefs(initial: LiveAlertConfig = LiveAlertConfig()) : AlertPrefs {
    private val state = MutableStateFlow(initial)
    override val config: Flow<LiveAlertConfig> = state
    override suspend fun update(transform: (LiveAlertConfig) -> LiveAlertConfig) = state.update(transform)
}

internal class DataStoreAlertPrefs(private val store: DataStore<Preferences>) : AlertPrefs {
    override val config: Flow<LiveAlertConfig> = store.data.map { p ->
        var c = LiveAlertConfig()
        LiveAlertKind.entries.forEach { k ->
            val d = c.of(k)
            c = c.with(k, AlertThreshold(p[on(k)] ?: d.enabled, p[value(k)] ?: d.value))
        }
        c.copy(newDtc = p[NEW_DTC] ?: c.newDtc)
    }

    override suspend fun update(transform: (LiveAlertConfig) -> LiveAlertConfig) {
        val next = transform(config.first())
        store.edit { p ->
            LiveAlertKind.entries.forEach { k ->
                p[on(k)] = next.of(k).enabled
                p[value(k)] = next.of(k).value
            }
            p[NEW_DTC] = next.newDtc
        }
    }

    private companion object {
        val NEW_DTC = booleanPreferencesKey("new_dtc")
        fun on(k: LiveAlertKind) = booleanPreferencesKey("${k.name.lowercase()}_on")
        fun value(k: LiveAlertKind) = doublePreferencesKey("${k.name.lowercase()}_value")
    }
}

/** Posted by the optional background module (channel «Trip warnings»); without it alerts are only on screen. */
interface TripAlertNotifier {
    /** Heads-up notification with sound/vibration (once per cooldown). */
    fun liveAlert(kind: LiveAlertKind, value: Double)
    fun clearLiveAlert(kind: LiveAlertKind)

    /** Silent notification for a code that appeared during the trip; tap opens the DTC screen. */
    fun newCode(code: String)
}

data class ActiveAlert(val kind: LiveAlertKind, val value: Double)

/** What the dashboard shows: active threshold alerts and codes that appeared during this connection. */
interface LiveAlerts {
    val active: StateFlow<List<ActiveAlert>>
    val newCodes: StateFlow<List<String>>
    fun dismissNewCodes()
}

/**
 * C4 live alerts while connected: readings → [LiveAlertEvaluator] (coolant, voltage with rpm), and every
 * [dtcEveryMs] a mode 03 + 07 read → [NewDtcTracker] (the first read is the baseline). Polls coolant, rpm and module
 * voltage as owner [OWNER] while any threshold is on. Nothing is stored: alerts and their cooldown live in memory.
 */
class LiveAlertMonitor internal constructor(
    private val controller: ConnectionController,
    private val prefs: AlertPrefs,
    private val scope: CoroutineScope,
    private val notifier: () -> TripAlertNotifier?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dtcEveryMs: Long = DTC_EVERY_MS,
) : LiveAlerts {
    private val evaluator = LiveAlertEvaluator()
    private val tracker = NewDtcTracker()
    private val _active = MutableStateFlow<List<ActiveAlert>>(emptyList())
    override val active: StateFlow<List<ActiveAlert>> = _active.asStateFlow()
    private val _newCodes = MutableStateFlow<List<String>>(emptyList())
    override val newCodes: StateFlow<List<String>> = _newCodes.asStateFlow()

    override fun dismissNewCodes() {
        _newCodes.value = emptyList()
    }

    fun start() {
        scope.launch {
            prefs.config.collect { c ->
                evaluator.config = c
                publish()
            }
        }
        scope.launch {
            combine(controller.state, prefs.config) { s, c -> s to c }.collect { (s, c) ->
                when (s) {
                    is ConnectionState.Connected -> {
                        val any = LiveAlertKind.entries.any { c.of(it).enabled }
                        controller.setPolledPids(OWNER, if (any) POLLED.mapNotNull(StandardPids::byCode).filter { it.code in s.supportedPids } else emptyList())
                    }
                    is ConnectionState.EngineOff, ConnectionState.Disconnected, is ConnectionState.Failed -> {
                        if (s !is ConnectionState.EngineOff) controller.setPolledPids(OWNER, emptyList())
                        if (_active.value.isNotEmpty()) {
                            _active.value.forEach { notifier()?.clearLiveAlert(it.kind) }
                        }
                        evaluator.reset()
                        publish()
                        if (s !is ConnectionState.EngineOff) {
                            tracker.reset()
                            _newCodes.value = emptyList()
                        }
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            controller.readings.collect { r ->
                if (controller.state.value !is ConnectionState.Connected) return@collect
                val changes = evaluator.onReading(r.sensorId, r.value, clock())
                if (changes.isNotEmpty()) handle(changes)
            }
        }
        scope.launch {
            val connectedWithCodes = combine(controller.state, prefs.config) { s, c -> s is ConnectionState.Connected && c.newDtc }
                .distinctUntilChanged()
            connectedWithCodes.collectLatest { on ->
                if (!on) return@collectLatest
                while (true) {
                    readCodes()
                    delay(dtcEveryMs)
                }
            }
        }
    }

    private suspend fun readCodes() {
        val codes = try {
            controller.withSession { s -> s.readDtcs(DtcKind.STORED) + s.readDtcs(DtcKind.PENDING) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        val fresh = tracker.onRead(codes.map { it.code })
        if (fresh.isEmpty()) return
        _newCodes.update { (it + fresh).distinct() }
        fresh.forEach { notifier()?.newCode(it) }
    }

    private fun handle(changes: List<LiveAlertChange>) {
        changes.forEach { ch ->
            if (ch.raised && ch.notify) notifier()?.liveAlert(ch.kind, ch.value)
            if (!ch.raised) notifier()?.clearLiveAlert(ch.kind)
        }
        publish()
    }

    private fun publish() {
        _active.value = evaluator.activeKinds.map { ActiveAlert(it, evaluator.valueOf(it) ?: 0.0) }
    }

    internal companion object {
        const val OWNER = "alerts"
        const val DTC_EVERY_MS = 60_000L
        /** Coolant, rpm, control module voltage; elm.voltage comes from ATRV anyway. */
        val POLLED = listOf(0x05, 0x0C, 0x42)
    }
}

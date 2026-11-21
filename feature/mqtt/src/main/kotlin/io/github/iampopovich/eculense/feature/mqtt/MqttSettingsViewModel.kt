package io.github.iampopovich.eculense.feature.mqtt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.model.LocalNetwork
import io.github.iampopovich.eculense.core.mqtt.ConnectResult
import io.github.iampopovich.eculense.core.mqtt.HaDiscovery
import io.github.iampopovich.eculense.core.mqtt.MqttConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface CheckState {
    data object Idle : CheckState
    data object Running : CheckState
    data class Done(val result: ConnectResult) : CheckState
}

internal data class MqttUiState(
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val host: String = "",
    val port: String = MqttSettings.DEFAULT_PORT.toString(),
    val tls: Boolean = false,
    val username: String = "",
    /** What the user typed; empty = keep the stored password (never shown back). */
    val password: String = "",
    val hasPassword: Boolean = false,
    val prefix: String = MqttSettings.DEFAULT_PREFIX,
    val period: String = MqttSettings.DEFAULT_PERIOD_SEC.toString(),
    val sendDashboard: Boolean = true,
    val sendBattery: Boolean = true,
    val sendConnection: Boolean = true,
    val sendDtcCount: Boolean = true,
    val check: CheckState = CheckState.Idle,
    val bridge: BridgeStatus = BridgeStatus.Off,
) {
    val portValue: Int? get() = port.trim().toIntOrNull()?.takeIf { it in 1..65_535 }
    val periodValue: Int? get() = period.trim().toIntOrNull()?.takeIf { it in MqttSettings.MIN_PERIOD_SEC..MqttSettings.MAX_PERIOD_SEC }

    /** Plain TCP is only for local-network hosts (same rule as the AI «Other server»). */
    val plainNotAllowed: Boolean get() = !tls && host.isNotBlank() && !LocalNetwork.isLocalHost(host.trim())
    val canCheck: Boolean get() = host.isNotBlank() && portValue != null && !plainNotAllowed && check != CheckState.Running

    /** Error prevention: publishing can be switched on only after the broker accepted these exact settings. */
    val canEnable: Boolean get() = enabled || (check as? CheckState.Done)?.result == ConnectResult.Accepted
}

/**
 * «Smart home (MQTT)»: broker fields, values to publish, «Check connection» (CONNECT → CONNACK → DISCONNECT with a
 * separate client id, so a running bridge is not kicked) and the on/off switch, locked until a check succeeded.
 * Edits are stored on their own after [debounceMs] in [saveScope] (the app scope), like the alert thresholds; the
 * password is encrypted by the store and never put back into the UI state.
 */
internal class MqttSettingsViewModel(
    private val store: MqttSettingsSource,
    private val probe: suspend (MqttConfig) -> ConnectResult,
    bridgeStatus: Flow<BridgeStatus> = flowOf(BridgeStatus.Off),
    saveScope: CoroutineScope? = null,
    private val debounceMs: Long = DEBOUNCE_MS,
) : ViewModel() {
    private val scope = saveScope ?: viewModelScope
    private val _state = MutableStateFlow(MqttUiState())
    val state: StateFlow<MqttUiState> = _state.asStateFlow()
    private var pending: Job? = null

    init {
        viewModelScope.launch {
            val s = store.settings.first()
            _state.value = MqttUiState(
                loaded = true, enabled = s.enabled, host = s.host, port = s.port.toString(), tls = s.tls, username = s.username,
                hasPassword = s.hasPassword, prefix = s.prefix, period = s.periodSec.toString(), sendDashboard = s.sendDashboard,
                sendBattery = s.sendBattery, sendConnection = s.sendConnection, sendDtcCount = s.sendDtcCount,
            )
        }
        viewModelScope.launch { bridgeStatus.collect { b -> _state.update { it.copy(bridge = b) } } }
    }

    /** Broker fields: a change resets the last check (the switch locks again unless publishing is already on). */
    fun setHost(v: String) = editBroker { it.copy(host = v) }
    fun setPort(v: String) = editBroker { it.copy(port = v.filter(Char::isDigit).take(5)) }
    fun setTls(on: Boolean) = editBroker { s ->
        // Swap the well-known default port with the TLS switch; a custom port stays.
        val port = when {
            on && s.port == MqttSettings.DEFAULT_PORT.toString() -> MqttSettings.DEFAULT_TLS_PORT.toString()
            !on && s.port == MqttSettings.DEFAULT_TLS_PORT.toString() -> MqttSettings.DEFAULT_PORT.toString()
            else -> s.port
        }
        s.copy(tls = on, port = port)
    }
    fun setUsername(v: String) = editBroker { it.copy(username = v) }
    fun setPassword(v: String) = editBroker { it.copy(password = v) }
    fun clearPassword() {
        _state.update { it.copy(password = "", hasPassword = false, check = CheckState.Idle) }
        scope.launch { store.setPassword("") }
    }

    fun setPrefix(v: String) = edit { it.copy(prefix = v) }
    fun setPeriod(v: String) = edit { it.copy(period = v.filter(Char::isDigit).take(4)) }
    fun setSendDashboard(on: Boolean) = edit { it.copy(sendDashboard = on) }
    fun setSendBattery(on: Boolean) = edit { it.copy(sendBattery = on) }
    fun setSendConnection(on: Boolean) = edit { it.copy(sendConnection = on) }
    fun setSendDtcCount(on: Boolean) = edit { it.copy(sendDtcCount = on) }

    fun setEnabled(on: Boolean) {
        val s = _state.value
        if (on && !s.canEnable) return
        _state.update { it.copy(enabled = on) }
        pending?.cancel()
        scope.launch { save(_state.value) }
    }

    fun check() {
        val s = _state.value
        if (!s.canCheck) return
        _state.update { it.copy(check = CheckState.Running) }
        pending?.cancel()
        viewModelScope.launch {
            save(_state.value)
            val saved = store.settings.first()
            val user = saved.username.ifBlank { null }
            val config = MqttConfig(
                host = saved.host, port = saved.port, tls = saved.tls,
                // Own client id: a running bridge with the main id stays connected.
                clientId = "eculense" + store.installId() + "c",
                username = user, password = if (user != null) store.password() else null,
                keepAliveSec = 30, will = null,
            )
            val result = probe(config)
            _state.update { it.copy(check = CheckState.Done(result)) }
        }
    }

    private fun editBroker(change: (MqttUiState) -> MqttUiState) = edit { change(it).copy(check = CheckState.Idle) }

    private fun edit(change: (MqttUiState) -> MqttUiState) {
        _state.update(change)
        pending?.cancel()
        pending = scope.launch {
            delay(debounceMs)
            save(_state.value)
        }
    }

    private suspend fun save(s: MqttUiState) {
        if (!s.loaded) return
        store.update { old ->
            old.copy(
                enabled = s.enabled, host = s.host.trim(), port = s.portValue ?: old.port, tls = s.tls, username = s.username.trim(),
                prefix = HaDiscovery.cleanPrefix(s.prefix), periodSec = s.periodValue ?: old.periodSec, sendDashboard = s.sendDashboard,
                sendBattery = s.sendBattery, sendConnection = s.sendConnection, sendDtcCount = s.sendDtcCount,
            )
        }
        if (s.password.isNotEmpty()) {
            store.setPassword(s.password)
            _state.update { it.copy(hasPassword = true) }
        }
    }

    internal companion object {
        const val DEBOUNCE_MS = 600L
    }
}

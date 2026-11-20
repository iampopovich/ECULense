package io.github.iampopovich.eculense.feature.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.ReplayLogHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class ConnectViewModel(
    private val controller: ConnectionController,
    private val replayLog: ReplayLogHolder? = null,
) : ViewModel() {
    private val _endpoints = MutableStateFlow<List<AdapterEndpoint>>(emptyList())
    val endpoints: StateFlow<List<AdapterEndpoint>> = _endpoints.asStateFlow()
    val state: StateFlow<ConnectionState> = controller.state

    fun refresh() {
        // Paired BT first, then Wi-Fi/USB, demo mode always last (Koin getAll() order is not guaranteed).
        viewModelScope.launch { _endpoints.value = controller.discover().sortedBy { kindRank(it.kind) } }
    }

    private fun kindRank(kind: String): Int = when (kind) {
        "bt" -> 0
        "demo" -> 2
        "demo-flaky" -> 3
        "demo-engine-off" -> 4
        "replay" -> 5
        else -> 1
    }

    fun connect(endpoint: AdapterEndpoint) = controller.connect(endpoint)

    /** The replay adapter: [log] is the exported traffic log the user picked (text). */
    fun connectReplay(endpoint: AdapterEndpoint, log: String) {
        replayLog?.text = log
        controller.connect(endpoint)
    }

    fun disconnect() = controller.disconnect()
}

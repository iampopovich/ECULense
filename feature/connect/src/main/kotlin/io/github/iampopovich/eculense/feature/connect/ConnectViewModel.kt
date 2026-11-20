package io.github.iampopovich.eculense.feature.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.ObdDiagnostics
import io.github.iampopovich.eculense.core.obd.PollStats
import io.github.iampopovich.eculense.core.obd.ReplayLogHolder
import io.github.iampopovich.eculense.core.obd.ReplayTransportFactory
import io.github.iampopovich.eculense.core.obd.VehicleInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class ConnectViewModel(
    private val controller: ConnectionController,
    private val replayLog: ReplayLogHolder? = null,
) : ViewModel() {
    private val _endpoints = MutableStateFlow<List<AdapterEndpoint>>(emptyList())
    val endpoints: StateFlow<List<AdapterEndpoint>> = _endpoints.asStateFlow()
    val state: StateFlow<ConnectionState> = controller.state

    /** B5: measured polling speed of the current connection (null before the first cycles). */
    val pollStats: StateFlow<PollStats?> = controller.pollStats

    /** B7: VIN / calibration ids / CVNs, read once per connection; null while unknown. */
    private val _vehicleInfo = MutableStateFlow<VehicleInfo?>(null)
    val vehicleInfo: StateFlow<VehicleInfo?> = _vehicleInfo.asStateFlow()

    init {
        viewModelScope.launch {
            controller.state.map { (it as? ConnectionState.Connected)?.endpoint }.distinctUntilChanged().collect { endpoint ->
                _vehicleInfo.value = null
                // A replayed log only answers the commands it recorded: no extra mode 09 requests there.
                if (endpoint != null && endpoint.kind != ReplayTransportFactory.KIND) {
                    _vehicleInfo.value = runCatching { controller.withSession { ObdDiagnostics(it).vehicleInfo() } }.getOrNull() ?: VehicleInfo()
                }
            }
        }
    }

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

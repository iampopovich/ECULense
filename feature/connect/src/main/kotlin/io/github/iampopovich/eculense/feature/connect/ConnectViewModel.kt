package io.github.iampopovich.eculense.feature.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.AdapterScanner
import io.github.iampopovich.eculense.core.obd.BleScanRules
import io.github.iampopovich.eculense.core.obd.ScanStart
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.ObdDiagnostics
import io.github.iampopovich.eculense.core.obd.PollStats
import io.github.iampopovich.eculense.core.obd.ReplayLogHolder
import io.github.iampopovich.eculense.core.obd.ReplayTransportFactory
import io.github.iampopovich.eculense.core.obd.VehicleInfo
import io.github.iampopovich.eculense.core.obd.isSimulated
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * BLE section state (A): [rows] = the scanned devices shown (likely adapters, or all with [showAll]), [hidden] = how
 * many non-adapter devices «show all» would add, [lastStart] = why the last scan did (not) start.
 */
internal data class BleSection(
    val rows: List<BleRow> = emptyList(),
    val hidden: Int = 0,
    val scanning: Boolean = false,
    val showAll: Boolean = false,
    val lastStart: ScanStart? = null,
)

/** One BLE device row; [alsoClassic] = a paired classic adapter has the same name (dual-mode Viecar 4.0). */
internal data class BleRow(val endpoint: AdapterEndpoint, val likelyObd: Boolean, val alsoClassic: Boolean, val serviceOnly: Boolean = false)

internal class ConnectViewModel(
    private val controller: ConnectionController,
    private val replayLog: ReplayLogHolder? = null,
    /** Scanning transports (BLE); null when :core:ble is not in the app. */
    private val scanner: AdapterScanner? = null,
) : ViewModel() {
    private val _endpoints = MutableStateFlow<List<AdapterEndpoint>>(emptyList())
    val endpoints: StateFlow<List<AdapterEndpoint>> = _endpoints.asStateFlow()
    val state: StateFlow<ConnectionState> = controller.state

    /** B5: measured polling speed of the current connection (null before the first cycles). */
    val pollStats: StateFlow<PollStats?> = controller.pollStats

    /** B7: VIN / calibration ids / CVNs, read once per connection; null while unknown. */
    private val _vehicleInfo = MutableStateFlow<VehicleInfo?>(null)
    val vehicleInfo: StateFlow<VehicleInfo?> = _vehicleInfo.asStateFlow()

    private val showAll = MutableStateFlow(false)
    private val lastStart = MutableStateFlow<ScanStart?>(null)

    /** null = no BLE support registered: the screen shows no BLE section. */
    val ble: StateFlow<BleSection?> = if (scanner == null) {
        MutableStateFlow(null)
    } else {
        combine(scanner.found, scanner.scanning, showAll, lastStart, _endpoints) { found, scanning, all, start, endpoints ->
            val classic = endpoints.filter { it.kind == "bt" }.map { it.label }
            val visible = if (all) found else found.filter { it.likelyObd }
            BleSection(
                rows = visible.map { BleRow(it.endpoint, it.likelyObd, BleScanRules.alsoPairedAsClassic(it.endpoint.label, classic), it.serviceOnly) },
                hidden = found.size - found.count { it.likelyObd },
                scanning = scanning,
                showAll = all,
                lastStart = start,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, BleSection())
    }

    /** Starts a BLE scan (the screen calls it when it becomes visible and on «Search again»). */
    fun startScan() {
        val s = scanner ?: return
        lastStart.value = s.start()
    }

    /** Stops scanning (screen left): BLE scans cost battery and Android limits scan starts. */
    fun stopScan() {
        scanner?.stop()
    }

    fun setShowAll(value: Boolean) {
        showAll.value = value
    }

    override fun onCleared() {
        scanner?.stop()
    }

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
        // Paired BT first, then Wi-Fi/USB (BLE devices come from the scanner, in their own section); simulated endpoints (debug builds only) last, in EndpointLabel order
        // (Koin getAll() order is not guaranteed).
        viewModelScope.launch {
            _endpoints.value = controller.discover().sortedWith(
                compareBy<AdapterEndpoint>({ it.isSimulated }, { if (it.kind == "bt") 0 else 1 }, { if (it.isSimulated) it.labelKey?.ordinal ?: 0 else 0 }),
            )
        }
    }

    fun connect(endpoint: AdapterEndpoint) = controller.connect(endpoint)

    /** The replay adapter: [log] is the exported traffic log the user picked (text). */
    fun connectReplay(endpoint: AdapterEndpoint, log: String) {
        replayLog?.text = log
        controller.connect(endpoint)
    }

    fun disconnect() = controller.disconnect()
}

package io.github.iampopovich.eculense.feature.cardata

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.analytics.TripCalculator
import io.github.iampopovich.eculense.core.data.DashboardPrefs
import io.github.iampopovich.eculense.core.data.DashboardSelection
import io.github.iampopovich.eculense.core.data.ExtendedSensors
import io.github.iampopovich.eculense.core.data.UnitsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.Pid
import io.github.iampopovich.eculense.core.obd.PidSystem
import io.github.iampopovich.eculense.core.obd.StandardPids
import io.github.iampopovich.eculense.core.obd.isSessionAlive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class CarDataRow(
    val pid: Pid,
    /** Display units; null = no value yet. */
    val value: Double?,
    val unit: String,
    val onPanel: Boolean,
    /** Fuel rate the car does not report: the dashboard estimates it from MAF. */
    val estimated: Boolean = false,
    val notAnswering: Boolean = false,
)

internal data class CarDataGroup(val system: PidSystem, val rows: List<CarDataRow>)

internal data class CarDataUiState(
    val connected: Boolean = false,
    val groups: List<CarDataGroup> = emptyList(),
    /** Known PIDs this car does not support (shown collapsed at the bottom as N/A). */
    val unsupported: List<Pid> = emptyList(),
    /** Dashboard selection in display order. */
    val panel: List<Pid> = emptyList(),
    val editing: Boolean = false,
    val keepScreenOn: Boolean = true,
    /** R-E: extended OBDb sensors and the EV battery card. */
    val extended: ExtendedUi = ExtendedUi(),
)

/**
 * C1: everything the car reports, grouped by system, read while the screen is open (owner [OWNER], tiers apply),
 * and the dashboard PID selection of the active car (star = on the dashboard; order is edited with up/down).
 */
internal class CarDataViewModel(
    private val controller: ConnectionController,
    private val prefs: DashboardPrefs,
    units: UnitsRepository,
    private val extended: ExtendedSensors? = null,
) : ViewModel() {
    private val latest = MutableStateFlow<Map<String, Double>>(emptyMap())
    private val editing = MutableStateFlow(false)

    private val lastConnected: StateFlow<ConnectionState> =
        controller.state.filter { it is ConnectionState.Connected || it is ConnectionState.Disconnected || it is ConnectionState.Failed }
            .stateIn(viewModelScope, SharingStarted.Eagerly, controller.state.value)

    private val panelCodes = combine(lastConnected, prefs.selection, prefs.fuelEstimate) { s, saved, estimate ->
        DashboardSelection.resolve(saved, (s as? ConnectionState.Connected)?.supportedPids.orEmpty(), estimate)
    }

    private val baseState = combine(
        combine(controller.state, lastConnected, controller.disabledPids) { s, c, d -> Triple(s, c, d) },
        panelCodes,
        latest,
        combine(editing, prefs.keepScreenOn, prefs.fuelEstimate) { e, k, f -> Triple(e, k, f) },
        units.units,
    ) { (state, connected, disabled), panel, values, (edit, keep, estimate), u ->
        val supported = (connected as? ConnectionState.Connected)?.supportedPids.orEmpty()
        val rows = rowsFor(supported, estimate).map { pid ->
            val estimated = pid.code == DashboardSelection.FUEL_RATE && pid.code !in supported
            val raw = if (estimated) {
                values[StandardPids.byCode(DashboardSelection.MAF)!!.sensorId]
                    ?.let { it * 3_600 / (TripCalculator.PETROL_AFR * TripCalculator.PETROL_DENSITY_G_PER_L) }
            } else {
                values[pid.sensorId]
            }
            CarDataRow(
                pid = pid,
                value = raw?.let { u.display(it, pid.unit).first },
                unit = u.display(0.0, pid.unit).second,
                onPanel = pid.code in panel,
                estimated = estimated,
                notAnswering = pid.code in disabled,
            )
        }
        CarDataUiState(
            connected = state.isSessionAlive,
            groups = rows.groupBy { StandardPids.systemOf(it.pid.code) }.toSortedMap().map { (system, list) -> CarDataGroup(system, list) },
            unsupported = if (connected is ConnectionState.Connected) StandardPids.all.filter { !DashboardSelection.canShow(it.code, supported, estimate) } else emptyList(),
            panel = panel.mapNotNull { StandardPids.byCode(it) },
            editing = edit,
            keepScreenOn = keep,
        )
    }

    private val extendedUi: Flow<ExtendedUi> = extended?.let { x ->
        combine(x.enabled, x.status, latest, controller.disabledExtended, units.units) { on, status, values, off, u -> ExtendedUi.of(on, status, values, off, u) }
    } ?: flowOf(ExtendedUi())

    val uiState: StateFlow<CarDataUiState> = combine(baseState, extendedUi) { s, e -> s.copy(extended = e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CarDataUiState())

    init {
        viewModelScope.launch {
            lastConnected.collect { s ->
                val polled = if (s is ConnectionState.Connected) StandardPids.all.filter { it.code in s.supportedPids } else emptyList()
                controller.setPolledPids(OWNER, polled)
            }
        }
        viewModelScope.launch { controller.readings.collect { r -> latest.update { it + (r.sensorId to r.value) } } }
    }

    override fun onCleared() {
        controller.setPolledPids(OWNER, emptyList())
    }

    internal fun onClearedForTest() = onCleared()

    fun togglePanel(code: Int) = edit { DashboardSelection.toggle(it, code) }
    fun moveUp(code: Int) = edit { DashboardSelection.moveUp(it, code) }
    fun moveDown(code: Int) = edit { DashboardSelection.moveDown(it, code) }
    fun setEditing(on: Boolean) { editing.value = on }
    fun setKeepScreenOn(on: Boolean) { viewModelScope.launch { prefs.setKeepScreenOn(on) } }

    /** R-E: «Extended sensors (OBDb)» on/off (off by default). */
    fun setExtended(on: Boolean) { extended?.setEnabled(on) }

    private fun edit(change: (List<Int>) -> List<Int>) {
        viewModelScope.launch {
            val supported = (lastConnected.value as? ConnectionState.Connected)?.supportedPids ?: return@launch
            val current = DashboardSelection.resolve(prefs.selection.first(), supported)
            prefs.setSelection(change(current))
        }
    }

    internal companion object {
        const val OWNER = "cardata"

        /** Supported known PIDs, plus the fuel rate when it can be estimated from MAF (not for diesel). */
        fun rowsFor(supported: Set<Int>, estimate: Boolean = true): List<Pid> =
            StandardPids.all.filter { DashboardSelection.canShow(it.code, supported, estimate) }
    }
}

package io.github.iampopovich.eculense.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.data.SessionRecorder
import io.github.iampopovich.eculense.core.data.UnitsRepository
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.Pid
import io.github.iampopovich.eculense.core.obd.StandardPids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class LiveValue(val pid: Pid, val value: Double?, val unit: String = pid.unit)

internal data class DashboardUiState(
    val connected: Boolean = false,
    val values: List<LiveValue> = emptyList(),
    val recording: Boolean = false,
    /** Короткая строка «адаптер · протокол · напряжение» — только когда подключены. */
    val status: String? = null,
)

internal fun ConnectionState.statusLine(): String? {
    if (this !is ConnectionState.Connected) return null
    val voltage = info.batteryVoltage?.let { " · " + String.format(Locale.ROOT, "%.1f", it) + " В" }.orEmpty()
    return "${endpoint.label} · протокол ${info.protocolNumber}$voltage"
}

internal class DashboardViewModel(
    private val controller: ConnectionController,
    private val recorder: SessionRecorder,
    units: UnitsRepository,
) : ViewModel() {
    private val latest = MutableStateFlow<Map<String, Double>>(emptyMap())

    private val selected: StateFlow<List<Pid>> = controller.state.map { state ->
        if (state is ConnectionState.Connected) {
            StandardPids.dashboardDefaults.filter { it in state.supportedPids }.mapNotNull { StandardPids.byCode(it) }
        } else {
            emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Значения хранятся метрическими; км/ч → mph, °C → °F и т. п. — только при показе. */
    val uiState: StateFlow<DashboardUiState> =
        combine(controller.state, selected, latest, recorder.activeSessionId, units.units) { state, pids, values, sessionId, u ->
            DashboardUiState(
                connected = state is ConnectionState.Connected,
                values = pids.map { pid ->
                    val raw = values[pid.sensorId]
                    val (shown, unit) = raw?.let { u.display(it, pid.unit) } ?: (null to u.display(0.0, pid.unit).second)
                    LiveValue(pid, shown, unit)
                },
                recording = sessionId != null,
                status = state.statusLine(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    init {
        viewModelScope.launch { selected.collect { controller.setPolledPids(it) } }
        viewModelScope.launch { controller.readings.collect { r -> latest.update { it + (r.sensorId to r.value) } } }
    }

    fun toggleRecording() {
        if (recorder.activeSessionId.value == null) {
            recorder.start("Запись " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM HH:mm")))
        } else {
            recorder.stop()
        }
    }
}

package io.github.iampopovich.eculense.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.data.SessionRecorder
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

internal data class LiveValue(val pid: Pid, val value: Double?)

internal data class DashboardUiState(
    val connected: Boolean = false,
    val values: List<LiveValue> = emptyList(),
    val recording: Boolean = false,
)

internal class DashboardViewModel(
    private val controller: ConnectionController,
    private val recorder: SessionRecorder,
) : ViewModel() {
    private val latest = MutableStateFlow<Map<String, Double>>(emptyMap())

    private val selected: StateFlow<List<Pid>> = controller.state.map { state ->
        if (state is ConnectionState.Connected) {
            StandardPids.dashboardDefaults.filter { it in state.supportedPids }.mapNotNull { StandardPids.byCode(it) }
        } else {
            emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val uiState: StateFlow<DashboardUiState> =
        combine(controller.state, selected, latest, recorder.activeSessionId) { state, pids, values, sessionId ->
            DashboardUiState(
                connected = state is ConnectionState.Connected,
                values = pids.map { LiveValue(it, values[it.sensorId]) },
                recording = sessionId != null,
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

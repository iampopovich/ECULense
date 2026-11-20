package io.github.iampopovich.eculense.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.analytics.EngineMap
import io.github.iampopovich.eculense.core.analytics.EngineMaps
import io.github.iampopovich.eculense.core.data.SessionRepository
import io.github.iampopovich.eculense.core.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class EngineMapUiState(
    val loading: Boolean = true,
    val map: EngineMap? = null,
    val session: Session? = null,
    /** D5 before/after: the second recording, when chosen. */
    val other: EngineMap? = null,
    val otherSession: Session? = null,
)

/** D5: fuel trims of one recording (and optionally a second one) binned by rpm and load. Read-only. */
internal class EngineMapViewModel(
    private val sessionId: Long,
    private val compareWith: Long?,
    private val repository: SessionRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(EngineMapUiState())
    val state: StateFlow<EngineMapUiState> = _state.asStateFlow()

    /** Recordings to compare with (newest first, this one excluded). */
    val sessions: StateFlow<List<Session>> = repository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val map = build(sessionId)
            val other = compareWith?.let { build(it) }
            val list = runCatching { repository.observeSessions().first() }.getOrDefault(emptyList())
            _state.value = EngineMapUiState(false, map, list.firstOrNull { it.id == sessionId }, other, list.firstOrNull { it.id == compareWith })
        }
    }

    private suspend fun build(id: Long): EngineMap = withContext(Dispatchers.Default) {
        val readings = runCatching { repository.readings(id) }.getOrDefault(emptyList())
        EngineMaps.build(readings.asSequence().filter { it.sensorId in EngineMaps.SENSORS }.sortedBy { it.timestampMs }.map { Triple(it.sensorId, it.value, it.timestampMs) }.asIterable())
    }
}

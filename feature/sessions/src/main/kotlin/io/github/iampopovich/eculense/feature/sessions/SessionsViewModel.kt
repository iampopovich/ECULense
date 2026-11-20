package io.github.iampopovich.eculense.feature.sessions

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.SessionCsv
import io.github.iampopovich.eculense.core.data.SessionMover
import io.github.iampopovich.eculense.core.data.SessionRepository
import io.github.iampopovich.eculense.core.data.TripEvent
import io.github.iampopovich.eculense.core.data.TripEventRepository
import io.github.iampopovich.eculense.core.data.UnitsRepository
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.Session
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Recordings list. Car Scanner import moved to Settings → Import and backups (1.27, :feature:backup). */
internal class SessionsViewModel(
    private val repository: SessionRepository,
) : ViewModel() {
    val sessions: StateFlow<List<Session>> =
        repository.observeSessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}

internal class SessionDetailViewModel(
    private val sessionId: Long,
    private val repository: SessionRepository,
    private val context: Context,
    private val units: UnitsRepository,
    events: TripEventRepository? = null,
    private val mover: SessionMover? = null,
    cars: CarRepository? = null,
) : ViewModel() {
    private val _readings = MutableStateFlow<Map<String, List<SensorReading>>>(emptyMap())
    val readingsBySensor: StateFlow<Map<String, List<SensorReading>>> = _readings.asStateFlow()

    /** B10: stalls and marks of this recording. */
    val events: StateFlow<List<TripEvent>> = (events?.observe(sessionId) ?: flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** C8: the cars the recording can move to (the sheet shows only with more than one). */
    val cars: StateFlow<List<CarProfile>> = (cars?.observeCars() ?: flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _carId = MutableStateFlow<Long?>(null)
    val carId: StateFlow<Long?> = _carId.asStateFlow()

    /** Imported Car Scanner trip: up to ~120k values, loading takes seconds. */
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        viewModelScope.launch {
            _readings.value = withContext(Dispatchers.Default) { repository.readings(sessionId).groupBy { it.sensorId } }
            _loading.value = false
        }
        viewModelScope.launch { _carId.value = runCatching { mover?.carOf(sessionId) }.getOrNull() }
    }

    /** C8: the recording, its trip and events move to [carId]; findings of both cars are recomputed. */
    fun moveTo(carId: Long) {
        val m = mover ?: return
        viewModelScope.launch {
            if (m.move(sessionId, carId)) _carId.value = carId
        }
    }

    /** Writes CSV to cache/exports and returns a content:// Uri for Intent.ACTION_SEND. */
    suspend fun exportCsv(): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "ecu-session-$sessionId.csv")
        file.bufferedWriter().use { SessionCsv.write(repository.readings(sessionId), it, units.units.first()) }
        FileProvider.getUriForFile(context, "${context.packageName}.sessions.files", file)
    }
}

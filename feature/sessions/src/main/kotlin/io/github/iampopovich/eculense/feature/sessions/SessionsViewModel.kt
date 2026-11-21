package io.github.iampopovich.eculense.feature.sessions

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.ChartSeries
import io.github.iampopovich.eculense.core.data.ExportFiles
import io.github.iampopovich.eculense.core.data.SessionChart
import io.github.iampopovich.eculense.core.data.SessionCsv
import io.github.iampopovich.eculense.core.data.SessionMover
import io.github.iampopovich.eculense.core.data.SessionRepository
import io.github.iampopovich.eculense.core.data.TripEvent
import io.github.iampopovich.eculense.core.data.TripEventRepository
import io.github.iampopovich.eculense.core.data.UnitsRepository
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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

/**
 * Recording page. The readings are never loaded as a whole (an imported Car Scanner trip has ~120k values): the sensor
 * list and time range come from the index, the chart of the selected sensor is streamed into a min/max decimation of
 * at most [io.github.iampopovich.eculense.core.analytics.MinMaxDecimator.CHART_POINTS] points in the user's units.
 */
internal class SessionDetailViewModel(
    private val sessionId: Long,
    private val repository: SessionRepository,
    private val context: Context,
    private val units: UnitsRepository,
    events: TripEventRepository? = null,
    private val mover: SessionMover? = null,
    cars: CarRepository? = null,
) : ViewModel() {
    private val _sensors = MutableStateFlow<List<String>>(emptyList())
    val sensors: StateFlow<List<String>> = _sensors.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    /** B10: time of the event whose ±[EVENT_WINDOW_MS] window is shown; null = the whole recording. */
    private val _focus = MutableStateFlow<Long?>(null)
    val focus: StateFlow<Long?> = _focus.asStateFlow()

    private val _series = MutableStateFlow<ChartSeries?>(null)
    val series: StateFlow<ChartSeries?> = _series.asStateFlow()

    private val _startMs = MutableStateFlow<Long?>(null)
    val startMs: StateFlow<Long?> = _startMs.asStateFlow()

    /** B10: stalls and marks of this recording. */
    val events: StateFlow<List<TripEvent>> = (events?.observe(sessionId) ?: flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** C8: the cars the recording can move to (the sheet shows only with more than one). */
    val cars: StateFlow<List<CarProfile>> = (cars?.observeCars() ?: flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _carId = MutableStateFlow<Long?>(null)
    val carId: StateFlow<Long?> = _carId.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        viewModelScope.launch {
            val ids = withContext(Dispatchers.IO) { repository.sensorIds(sessionId) }
            _sensors.value = ids
            if (_selected.value == null) _selected.value = defaultSensor(ids)
            if (ids.isEmpty()) _loading.value = false
            _startMs.value = withContext(Dispatchers.IO) { repository.firstTimestamp(sessionId) }
        }
        viewModelScope.launch { loadSeries() }
        viewModelScope.launch { _carId.value = runCatching { mover?.carOf(sessionId) }.getOrNull() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun loadSeries() {
        combine(_selected.filterNotNull(), _focus, units.units) { s, f, u -> Triple(s, f, u) }.collectLatest { (sensor, at, u) ->
            _loading.value = true
            val started = SystemClock.elapsedRealtime()
            val s = withContext(Dispatchers.IO) { SessionChart.load(repository, sessionId, sensor, u, eventRange(at)) }
            _series.value = s
            _loading.value = false
            Log.i(TAG, "chart $sensor: ${s?.sourceCount ?: 0} readings -> ${s?.points?.size ?: 0} points in ${SystemClock.elapsedRealtime() - started} ms")
        }
    }

    fun select(sensorId: String) {
        _selected.value = sensorId
    }

    /** Tapping the focused event again shows the whole recording. */
    fun toggleFocus(atMs: Long) {
        _focus.value = if (_focus.value == atMs) null else atMs
    }

    /** C8: the recording, its trip and events move to [carId]; findings of both cars are recomputed. */
    fun moveTo(carId: Long) {
        val m = mover ?: return
        viewModelScope.launch {
            if (m.move(sessionId, carId)) _carId.value = carId
        }
    }

    /**
     * Streams the CSV into cache/exports (previous exports deleted) page by page and returns a content:// Uri for
     * Intent.ACTION_SEND.
     */
    suspend fun exportCsv(): Uri = withContext(Dispatchers.IO) {
        val file = ExportFiles.fresh(File(context.cacheDir, "exports"), "ecu-session-$sessionId.csv")
        val u = units.units.first()
        file.bufferedWriter().use { SessionCsv.write(repository, sessionId, it, u) }
        FileProvider.getUriForFile(context, "${context.packageName}.sessions.files", file)
    }

    private companion object {
        const val TAG = "EcuSessions"
    }
}

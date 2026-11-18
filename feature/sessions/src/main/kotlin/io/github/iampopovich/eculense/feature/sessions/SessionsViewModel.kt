package io.github.iampopovich.eculense.feature.sessions

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.data.SessionCsv
import io.github.iampopovich.eculense.core.data.SessionRepository
import io.github.iampopovich.eculense.core.data.UnitsRepository
import io.github.iampopovich.eculense.core.model.SensorReading
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
import java.io.File

/** Список записей. Импорт из Car Scanner переехал в «Настройки → Импорт и резервные копии» (1.27, :feature:backup). */
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
) : ViewModel() {
    private val _readings = MutableStateFlow<Map<String, List<SensorReading>>>(emptyMap())
    val readingsBySensor: StateFlow<Map<String, List<SensorReading>>> = _readings.asStateFlow()

    /** Импортированная поездка Car Scanner — до ~120 тыс. значений, загрузка занимает секунды. */
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        viewModelScope.launch {
            _readings.value = withContext(Dispatchers.Default) { repository.readings(sessionId).groupBy { it.sensorId } }
            _loading.value = false
        }
    }

    /** Пишет CSV в cache/exports и возвращает content:// Uri для Intent.ACTION_SEND. */
    suspend fun exportCsv(): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "ecu-session-$sessionId.csv")
        file.bufferedWriter().use { SessionCsv.write(repository.readings(sessionId), it, units.units.first()) }
        FileProvider.getUriForFile(context, "${context.packageName}.sessions.files", file)
    }
}

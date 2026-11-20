package io.github.iampopovich.eculense.feature.obdlog

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.obd.ObdTrafficLog
import io.github.iampopovich.eculense.core.obd.TrafficEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

internal class ObdLogViewModel(
    private val log: ObdTrafficLog,
    private val prefs: TrafficLogPrefs,
    private val files: TrafficLogFiles?,
    private val refreshMs: Long = 300,
) : ViewModel() {

    /** Newest first; refreshed at most every [refreshMs] (StateFlow is conflated, the delay throttles). */
    val entries: StateFlow<List<TrafficEntry>> = log.revision
        .transform {
            emit(log.snapshot().asReversed())
            delay(refreshMs)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recording: StateFlow<Boolean> = log.enabled

    fun setRecording(enabled: Boolean) {
        log.setEnabled(enabled)
        viewModelScope.launch { prefs.setRecording(enabled) }
    }

    fun clear() = log.clear()

    suspend fun export(): Uri? = files?.write(log.export())
}

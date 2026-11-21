package io.github.iampopovich.eculense.feature.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.HealthCheckRecord
import io.github.iampopovich.eculense.core.data.HealthProgress
import io.github.iampopovich.eculense.core.data.HealthRepository
import io.github.iampopovich.eculense.core.data.HealthCheckRunner
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class HealthUiState(
    val connected: Boolean = false,
    val progress: HealthProgress = HealthProgress.Idle,
    /** The check on screen: the one just run, or one opened from the history. */
    val shown: HealthCheckRecord? = null,
    /** false = the shown check was not saved (simulated link). */
    val shownStored: Boolean = true,
    val history: List<HealthCheckRecord> = emptyList(),
    val aiAvailable: Boolean = false,
) {
    val running: Boolean get() = progress is HealthProgress.ReadingCodes || progress is HealthProgress.Live
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class HealthViewModel internal constructor(
    progress: StateFlow<HealthProgress>,
    private val runCheck: suspend () -> Unit,
    private val repository: HealthRepository,
    controller: ConnectionController,
    cars: CarRepository,
    private val aiAvailable: Boolean,
    openCheckId: Long? = null,
) : ViewModel() {
    /** Koin: the runner's progress and run(). The internal constructor above is the JVM-test seam. */
    constructor(
        runner: HealthCheckRunner,
        repository: HealthRepository,
        controller: ConnectionController,
        cars: CarRepository,
        aiAvailable: Boolean,
        openCheckId: Long? = null,
    ) : this(runner.progress, { runner.run() }, repository, controller, cars, aiAvailable, openCheckId)

    private val opened = MutableStateFlow<Pair<HealthCheckRecord, Boolean>?>(null)
    private var job: Job? = null

    private val history = cars.observeActiveCar().flatMapLatest { car -> car?.let { repository.observe(it.id) } ?: flowOf(emptyList()) }

    val uiState: StateFlow<HealthUiState> = combine(controller.state, progress, opened, history) { state, progress, open, list ->
        val done = progress as? HealthProgress.Done
        val shown = open ?: done?.let { it.record to it.stored }
        HealthUiState(
            connected = state is ConnectionState.Connected,
            progress = progress,
            shown = shown?.first,
            shownStored = shown?.second ?: true,
            history = list,
            aiAvailable = aiAvailable,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthUiState(aiAvailable = aiAvailable))

    init {
        openCheckId?.let { id -> viewModelScope.launch { repository.byId(id)?.let { opened.value = it to true } } }
    }

    fun run() {
        if (job?.isActive == true) return
        opened.value = null
        job = viewModelScope.launch { runCheck() }
    }

    fun open(record: HealthCheckRecord) {
        opened.value = record to true
    }

    override fun onCleared() {
        job?.cancel()
    }
}

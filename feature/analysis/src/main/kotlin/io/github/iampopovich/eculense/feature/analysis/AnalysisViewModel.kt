package io.github.iampopovich.eculense.feature.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.data.AnalysisState
import io.github.iampopovich.eculense.core.data.AnalyticsRepository
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.SessionSummary
import io.github.iampopovich.eculense.core.data.StoredFinding
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.navigation.AiChatDestination
import io.github.iampopovich.eculense.core.navigation.RouteHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class AnalysisUiState(
    val loading: Boolean = true,
    val car: CarProfile? = null,
    val findings: List<StoredFinding> = emptyList(),
    val analysis: AnalysisState = AnalysisState.Idle,
) {
    val problems: List<StoredFinding> get() = findings.filter { it.finding.severity.rank >= Severity.INFO.rank }.sortedByDescending { it.finding.severity.rank }
    val noData: List<StoredFinding> get() = findings.filter { it.finding.severity == Severity.NO_DATA }
    val ok: List<StoredFinding> get() = findings.filter { it.finding.severity == Severity.OK }
    val running: Boolean get() = analysis is AnalysisState.Running
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class AnalysisViewModel(
    private val analytics: AnalyticsRepository,
    private val cars: CarRepository,
) : ViewModel() {
    private val activeCar = cars.observeActiveCar().distinctUntilChanged { a, b -> a?.id == b?.id && a == b }

    val uiState: StateFlow<AnalysisUiState> = combine(
        activeCar,
        activeCar.map { it?.id }.distinctUntilChanged().flatMapLatest { id -> id?.let { analytics.observeFindings(it) } ?: flowOf(emptyList()) },
        analytics.state,
    ) { car, findings, state -> AnalysisUiState(loading = false, car = car, findings = findings, analysis = state) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisUiState())

    init {
        // First visit: no findings yet — compute right away (in the background, with progress) instead of waiting for the button.
        viewModelScope.launch {
            val id = cars.activeCarId() ?: return@launch
            if (analytics.findings(id).isEmpty() && analytics.state.value !is AnalysisState.Running) recompute()
        }
    }

    fun recompute() {
        if (analytics.state.value is AnalysisState.Running) return
        viewModelScope.launch {
            val id = cars.observeActiveCar().first()?.id ?: return@launch
            runCatching { analytics.recompute(id) }
        }
    }
}

internal data class FindingUiState(
    val loading: Boolean = true,
    val finding: StoredFinding? = null,
    val sessions: List<SessionSummary> = emptyList(),
    val canAskAi: Boolean = false,
    /** Code descriptions from the evidence (evidence label = DTC code). */
    val dtc: Map<String, DtcNote> = emptyMap(),
)

/** What to show under the code: the title (or «заводской код, расшифровка неизвестна») and a couple of common causes. */
internal data class DtcNote(val text: String, val causes: List<String> = emptyList())

internal class FindingViewModel(
    private val findingId: Long,
    private val analytics: AnalyticsRepository,
    routeHandlers: List<RouteHandler>,
    private val describeDtc: suspend (Collection<String>) -> Map<String, DtcNote> = { emptyMap() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(FindingUiState(canAskAi = routeHandlers.any { it.routeClass == AiChatDestination::class }))
    val uiState: StateFlow<FindingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val f = analytics.finding(findingId)
            val ids = f?.finding?.sessionIds.orEmpty().toSet()
            val sessions = if (f == null || ids.isEmpty()) emptyList() else analytics.sessions(f.carId, limit = 10_000).filter { it.id in ids }
            val codes = f?.finding?.evidence.orEmpty().map { it.label }.filter { DTC_CODE.matches(it) }
            val dtc = if (codes.isEmpty()) emptyMap() else runCatching { describeDtc(codes) }.getOrDefault(emptyMap())
            _uiState.value = _uiState.value.copy(loading = false, finding = f, sessions = sessions, dtc = dtc)
        }
    }

    private companion object {
        val DTC_CODE = Regex("^[PBCU][0-3][0-9A-F]{3}$")
    }
}

/** Text for the chat: what the code found and on which numbers — the model explains rather than recomputes. */
internal fun askAiPrompt(f: StoredFinding): String = buildString {
    val x = f.finding
    append("Объясни находку анализа «${x.title}» (${x.severity.label}): ${x.message}")
    if (x.evidence.isNotEmpty()) append(" Доказательства: " + x.evidence.joinToString("; ") { "${it.label} — ${it.value}" } + ".")
    append(" Что это значит и что проверить?")
}

internal val Severity.label: String
    get() = when (this) {
        Severity.CRITICAL -> "Критично"
        Severity.WARNING -> "Внимание"
        Severity.INFO -> "Справка"
        Severity.NO_DATA -> "Нет данных"
        Severity.OK -> "В норме"
    }

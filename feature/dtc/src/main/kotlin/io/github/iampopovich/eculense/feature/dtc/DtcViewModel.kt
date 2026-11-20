package io.github.iampopovich.eculense.feature.dtc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.data.DtcRepository
import io.github.iampopovich.eculense.core.data.ReadinessReason
import io.github.iampopovich.eculense.core.dtcdb.DtcInfo
import io.github.iampopovich.eculense.core.dtcdb.DtcSource
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.FreezeFrame
import io.github.iampopovich.eculense.core.navigation.AiChatDestination
import io.github.iampopovich.eculense.core.navigation.RouteHandler
import io.github.iampopovich.eculense.core.obd.ClearedCounters
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.MonitorState
import io.github.iampopovich.eculense.core.obd.ObdDiagnostics
import io.github.iampopovich.eculense.core.obd.Readiness
import io.github.iampopovich.eculense.core.obd.RecentClearCheck
import io.github.iampopovich.eculense.core.obd.StandardPids
import io.github.iampopovich.eculense.core.obd.UsedCarCheck
import io.github.iampopovich.eculense.core.obdtext.ObdText
import io.github.iampopovich.eculense.core.ui.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Descriptions taking the active vehicle's make into account (manufacturer codes — only for its own make). */
internal fun interface DtcDescriber {
    suspend fun describe(codes: Collection<String>): Map<String, DtcInfo>
}

/** Event from the active vehicle's history: live reads and Car Scanner import. */
internal data class DtcHistoryEvent(val code: String, val kind: DtcKind, val atMs: Long)

internal fun interface DtcHistorySource {
    suspend fun events(): List<DtcHistoryEvent>
}

/** [freezeFrame]: B3, shown in the card of the code that stored it. */
internal data class DtcItem(val dtc: Dtc, val info: DtcInfo, val freezeFrame: FreezeFrame? = null) {
    val description: String? get() = info.title
}

/** Code from history: how many times it occurred and when it was last seen. */
internal data class DtcHistoryItem(val code: String, val kind: DtcKind, val count: Int, val lastMs: Long, val info: DtcInfo)

/** B4: whether the car stands still, from the latest speed reading (fresher than [DtcViewModel.SPEED_FRESH_MS]). */
internal enum class SpeedGate { STOPPED, MOVING, UNKNOWN }

/** What one diagnostic read found: codes, freeze frame, readiness (B1) and the cleared-codes counters (B2). */
internal data class DiagRead(
    val dtcs: List<Dtc>,
    val freezeFrame: FreezeFrame?,
    val readiness: Readiness?,
    val thisCycle: List<MonitorState>?,
    val counters: ClearedCounters?,
)

/** B4: the state before and after a clear, shown side by side. */
internal data class ClearComparison(
    val codesBefore: List<String>,
    val codesAfter: List<String>,
    val milBefore: Boolean?,
    val milAfter: Boolean?,
    val incompleteBefore: Int?,
    val incompleteAfter: Int?,
) {
    /** Codes that are back right after the clear: the fault is still there. */
    val returned: List<String> get() = codesAfter.filter { it in codesBefore }
}

internal data class DtcUiState(
    val loading: Boolean = false,
    val items: List<DtcItem> = emptyList(),
    val history: List<DtcHistoryItem> = emptyList(),
    val error: UiText? = null,
    val canAskAi: Boolean = false,
    /** Codes have been read successfully at least once: an empty list then means «ошибок нет» rather than «ещё не читали». */
    val checked: Boolean = false,
    val readiness: Readiness? = null,
    val thisCycle: List<MonitorState>? = null,
    val usedCar: RecentClearCheck? = null,
    val speed: SpeedGate = SpeedGate.UNKNOWN,
    val comparison: ClearComparison? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class DtcViewModel(
    private val controller: ConnectionController,
    private val descriptions: DtcDescriber,
    private val repository: DtcRepository,
    routeHandlers: List<RouteHandler>,
    private val historySource: DtcHistorySource = DtcHistorySource { emptyList() },
    private val now: () -> Long = System::currentTimeMillis,
    ticks: Flow<Unit> = untilStale(),
) : ViewModel() {
    private val state = MutableStateFlow(DtcUiState(canAskAi = routeHandlers.any { it.routeClass == AiChatDestination::class }))
    private val lastSpeed = MutableStateFlow<Pair<Double, Long>?>(null)

    val uiState: StateFlow<DtcUiState> = combine(state, lastSpeed.flatMapLatest { if (it == null) flowOf(Unit) else ticks }) { s, _ ->
        val gate = gateOf(lastSpeed.value, now())
        // The «stop the car» refusal is shown by the screen while moving; it must not outlive the movement.
        val error = s.error.takeUnless { it == MOVING_ERROR }
        s.copy(speed = gate, error = error)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, state.value)

    init {
        viewModelScope.launch { runCatching { loadHistory() } }
        // B4: speed is read while the screen is open, so clearing can be locked while the car moves.
        viewModelScope.launch {
            controller.state.collect { s ->
                val polled = if (s is ConnectionState.Connected && SPEED in s.supportedPids) listOfNotNull(StandardPids.byCode(SPEED)) else emptyList()
                controller.setPolledPids(OWNER, polled)
            }
        }
        viewModelScope.launch {
            controller.readings.collect { r -> if (r.sensorId == SPEED_ID) lastSpeed.value = r.value to now() }
        }
    }

    override fun onCleared() {
        controller.setPolledPids(OWNER, emptyList())
    }

    /** Every successful read (demo or real adapter) goes into the active vehicle's history — the AI reads it. */
    fun read() = launchGuarded {
        val result = readAll()
        if (result == null) {
            state.update { it.copy(error = UiText.Res(R.string.dtc_err_no_connection)) }
        } else {
            show(result)
            if (repository.recordRead(result.dtcs, null, result.freezeFrame)) loadHistory()
        }
    }

    /**
     * B4: refuse while moving; save codes + freeze frame + readiness + counters first (a clear erases them), clear,
     * read everything again and show before/after. [stationaryConfirmed]: the user ticked «the car stands» when no
     * speed reading is available.
     */
    fun clear(stationaryConfirmed: Boolean = false) = launchGuarded {
        when (gateOf(lastSpeed.value, now())) {
            SpeedGate.MOVING -> {
                state.update { it.copy(error = MOVING_ERROR) }
                return@launchGuarded
            }
            SpeedGate.UNKNOWN -> if (!stationaryConfirmed) {
                state.update { it.copy(error = UiText.Res(R.string.dtc_clear_confirm_stationary)) }
                return@launchGuarded
            }
            SpeedGate.STOPPED -> Unit
        }
        val before = readAll() ?: run {
            state.update { it.copy(error = UiText.Res(R.string.dtc_err_no_connection)) }
            return@launchGuarded
        }
        repository.recordRead(before.dtcs, null, before.freezeFrame)
        repository.saveReadiness(ReadinessReason.BEFORE_CLEAR, before.readiness, before.counters)
        val cleared = controller.withSession { it.clearDtcs() }
        val after = readAll()
        if (after != null) {
            show(after)
            repository.saveReadiness(ReadinessReason.AFTER_CLEAR, after.readiness, after.counters)
        }
        state.update {
            it.copy(
                error = if (cleared != true) UiText.Res(R.string.dtc_err_clear_not_confirmed) else null,
                comparison = after?.let { a ->
                    ClearComparison(
                        before.dtcs.map { d -> d.code }.distinct(), a.dtcs.map { d -> d.code }.distinct(),
                        before.readiness?.milOn, a.readiness?.milOn, before.readiness?.incompleteCount, a.readiness?.incompleteCount,
                    )
                },
            )
        }
        loadHistory()
    }

    private suspend fun readAll(): DiagRead? {
        val supported = (controller.state.value as? ConnectionState.Connected)?.supportedPids.orEmpty()
        return controller.withSession { session ->
            val dtcs = DtcKind.entries.flatMap { session.readDtcs(it) }
            val d = ObdDiagnostics(session, now)
            val frame = if (dtcs.any { it.kind == DtcKind.STORED }) d.freezeFrame() else null
            val readiness = d.readiness()
            val cycle = if (readiness != null && (supported.isEmpty() || 0x41 in supported)) d.readinessThisCycle(readiness.ignition) else null
            val counters = d.counters(supported).takeUnless { it.isEmpty }
            DiagRead(dtcs, frame, readiness, cycle, counters)
        }
    }

    private suspend fun show(result: DiagRead) {
        val frameCode = result.freezeFrame?.let { it.dtcCode ?: result.dtcs.firstOrNull { d -> d.kind == DtcKind.STORED }?.code }
        val items = describe(result.dtcs).map { item ->
            if (item.dtc.kind == DtcKind.STORED && item.dtc.code == frameCode) item.copy(freezeFrame = result.freezeFrame) else item
        }
        val usedCar = if (result.counters != null || result.readiness != null) {
            UsedCarCheck.evaluate(result.counters ?: ClearedCounters(), result.readiness)
        } else {
            null
        }
        state.update {
            it.copy(items = items, error = null, checked = true, readiness = result.readiness, thisCycle = result.thisCycle, usedCar = usedCar)
        }
    }

    private suspend fun describe(dtcs: List<Dtc>): List<DtcItem> {
        val infos = descriptions.describe(dtcs.map { it.code }.toSet())
        return dtcs.map { DtcItem(it, infos[it.code] ?: DtcInfo(it.code, null, DtcSource.UNKNOWN)) }
    }

    private suspend fun loadHistory() {
        val events = historySource.events()
        if (events.isEmpty()) return
        val infos = descriptions.describe(events.map { it.code }.toSet())
        val items = events.groupBy { it.code }.map { (code, list) ->
            val last = list.maxBy { it.atMs }
            DtcHistoryItem(code, last.kind, list.size, last.atMs, infos[code] ?: DtcInfo(code, null, DtcSource.UNKNOWN))
        }.sortedByDescending { it.lastMs }
        state.update { it.copy(history = items) }
    }

    private fun launchGuarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            state.update { it.copy(loading = true) }
            try {
                block()
            } catch (e: Exception) {
                state.update { it.copy(error = ObdText.error(e)) }
            } finally {
                state.update { it.copy(loading = false) }
            }
        }
    }

    internal fun onClearedForTest() = onCleared()

    internal companion object {
        const val OWNER = "dtc"
        const val SPEED = 0x0D
        const val SPEED_ID = "01.0D"
        const val SPEED_FRESH_MS = 2_000L
        val MOVING_ERROR: UiText = UiText.Res(R.string.dtc_clear_blocked_moving)

        fun gateOf(speed: Pair<Double, Long>?, nowMs: Long): SpeedGate = when {
            speed == null || nowMs - speed.second > SPEED_FRESH_MS -> SpeedGate.UNKNOWN
            speed.first > 0.0 -> SpeedGate.MOVING
            else -> SpeedGate.STOPPED
        }
    }
}

/**
 * After each speed reading: a few ticks until it is stale, then the flow ends (no endless ticker, so tests on virtual
 * time finish). A new reading restarts it.
 */
internal fun untilStale(): Flow<Unit> = flow {
    emit(Unit)
    repeat((DtcViewModel.SPEED_FRESH_MS / 500 + 2).toInt()) {
        delay(500)
        emit(Unit)
    }
}

/**
 * Text for the chat: the code, what is known about it (briefly), a request to explain. [str] resolves a string
 * resource with arguments in the app language (the question is the user's message, so it is localized).
 */
internal fun askAiPrompt(code: String, info: DtcInfo, str: (Int, List<Any>) -> String): String = buildString {
    append(str(R.string.dtc_ask_ai_code, listOf(code)))
    when (info.source) {
        DtcSource.OEM_UNKNOWN -> append(" (").append(str(R.string.dtc_ask_ai_oem_unknown, emptyList())).append(")")
        else -> info.title?.let { append(" ($it)") }
    }
    if (info.causes.isNotEmpty()) append(". ").append(str(R.string.dtc_ask_ai_causes, listOf(info.causes.take(3).joinToString("; "))))
    append(": ").append(str(R.string.dtc_ask_ai_tail, emptyList()))
}

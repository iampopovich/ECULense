package io.github.iampopovich.eculense.feature.dtc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.data.DtcRepository
import io.github.iampopovich.eculense.core.dtcdb.DtcInfo
import io.github.iampopovich.eculense.core.dtcdb.DtcSource
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.navigation.AiChatDestination
import io.github.iampopovich.eculense.core.navigation.RouteHandler
import io.github.iampopovich.eculense.core.obd.ConnectionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

internal data class DtcItem(val dtc: Dtc, val info: DtcInfo) {
    val description: String? get() = info.title
}

/** Code from history: how many times it occurred and when it was last seen. */
internal data class DtcHistoryItem(val code: String, val kind: DtcKind, val count: Int, val lastMs: Long, val info: DtcInfo)

internal data class DtcUiState(
    val loading: Boolean = false,
    val items: List<DtcItem> = emptyList(),
    val history: List<DtcHistoryItem> = emptyList(),
    val error: String? = null,
    val canAskAi: Boolean = false,
    /** Codes have been read successfully at least once: an empty list then means «ошибок нет» rather than «ещё не читали». */
    val checked: Boolean = false,
)

internal class DtcViewModel(
    private val controller: ConnectionController,
    private val descriptions: DtcDescriber,
    private val repository: DtcRepository,
    routeHandlers: List<RouteHandler>,
    private val historySource: DtcHistorySource = DtcHistorySource { emptyList() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(DtcUiState(canAskAi = routeHandlers.any { it.routeClass == AiChatDestination::class }))
    val uiState: StateFlow<DtcUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { runCatching { loadHistory() } }
    }

    /** Every successful read (demo or real adapter) goes into the active vehicle's history — the AI reads it. */
    fun read() = launchGuarded {
        val dtcs = controller.withSession { session -> DtcKind.entries.flatMap { session.readDtcs(it) } }
        if (dtcs == null) {
            _uiState.update { it.copy(error = "Нет подключения к адаптеру") }
        } else {
            val items = describe(dtcs)
            _uiState.update { it.copy(items = items, error = null, checked = true) }
            if (repository.recordRead(dtcs)) loadHistory()
        }
    }

    /** First save a snapshot (codes can't be recovered after clearing), then clear and re-read. */
    fun clear() = launchGuarded {
        repository.recordRead(_uiState.value.items.map { it.dtc })
        val cleared = controller.withSession { it.clearDtcs() }
        if (cleared != true) _uiState.update { it.copy(error = "ЭБУ не подтвердил сброс") }
        val dtcs = controller.withSession { session -> DtcKind.entries.flatMap { session.readDtcs(it) } }.orEmpty()
        val items = describe(dtcs)
        _uiState.update { it.copy(items = items, checked = true) }
        loadHistory()
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
        _uiState.update { it.copy(history = items) }
    }

    private fun launchGuarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            try {
                block()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(loading = false) }
            }
        }
    }
}

/** Text for the chat: the code, what is known about it (briefly), a request to explain. */
internal fun askAiPrompt(code: String, info: DtcInfo): String = buildString {
    append("Объясни код ошибки $code")
    when (info.source) {
        DtcSource.OEM_UNKNOWN -> append(" (заводской код производителя, расшифровка неизвестна)")
        else -> info.title?.let { append(" ($it)") }
    }
    if (info.causes.isNotEmpty()) append(". Частые причины по базе OBDex: ${info.causes.take(3).joinToString("; ")}")
    append(": причины, что проверить, насколько срочно.")
}

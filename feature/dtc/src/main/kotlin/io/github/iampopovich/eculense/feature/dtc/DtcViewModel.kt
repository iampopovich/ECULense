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

/** Расшифровки с учётом марки активной машины (заводские коды — только своей марки). */
internal fun interface DtcDescriber {
    suspend fun describe(codes: Collection<String>): Map<String, DtcInfo>
}

/** Событие из истории активной машины: живые чтения и импорт Car Scanner. */
internal data class DtcHistoryEvent(val code: String, val kind: DtcKind, val atMs: Long)

internal fun interface DtcHistorySource {
    suspend fun events(): List<DtcHistoryEvent>
}

internal data class DtcItem(val dtc: Dtc, val info: DtcInfo) {
    val description: String? get() = info.title
}

/** Код из истории: сколько раз встречался и когда последний раз. */
internal data class DtcHistoryItem(val code: String, val kind: DtcKind, val count: Int, val lastMs: Long, val info: DtcInfo)

internal data class DtcUiState(
    val loading: Boolean = false,
    val items: List<DtcItem> = emptyList(),
    val history: List<DtcHistoryItem> = emptyList(),
    val error: String? = null,
    val canAskAi: Boolean = false,
    /** Коды хотя бы раз успешно прочитаны: пустой список тогда значит «ошибок нет», а не «ещё не читали». */
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

    /** Каждое успешное чтение (демо или настоящий адаптер) попадает в историю активной машины — её читает ИИ. */
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

    /** Сначала сохранить снимок (после сброса восстановить коды невозможно), потом сбросить и перечитать. */
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

/** Текст для чата: код, что о нём известно (кратко), просьба объяснить. */
internal fun askAiPrompt(code: String, info: DtcInfo): String = buildString {
    append("Объясни код ошибки $code")
    when (info.source) {
        DtcSource.OEM_UNKNOWN -> append(" (заводской код производителя, расшифровка неизвестна)")
        else -> info.title?.let { append(" ($it)") }
    }
    if (info.causes.isNotEmpty()) append(". Частые причины по базе OBDex: ${info.causes.take(3).joinToString("; ")}")
    append(": причины, что проверить, насколько срочно.")
}

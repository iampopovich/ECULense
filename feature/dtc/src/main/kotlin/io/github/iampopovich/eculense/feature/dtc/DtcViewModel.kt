package io.github.iampopovich.eculense.feature.dtc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.data.DtcRepository
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

internal data class DtcItem(val dtc: Dtc, val description: String?)

internal data class DtcUiState(
    val loading: Boolean = false,
    val items: List<DtcItem> = emptyList(),
    val error: String? = null,
    val canAskAi: Boolean = false,
    /** Коды хотя бы раз успешно прочитаны: пустой список тогда значит «ошибок нет», а не «ещё не читали». */
    val checked: Boolean = false,
)

internal class DtcViewModel(
    private val controller: ConnectionController,
    private val descriptions: DtcDescriptions,
    private val history: DtcRepository,
    routeHandlers: List<RouteHandler>,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DtcUiState(canAskAi = routeHandlers.any { it.routeClass == AiChatDestination::class }))
    val uiState: StateFlow<DtcUiState> = _uiState.asStateFlow()

    fun read() = launchGuarded {
        val dtcs = controller.withSession { session -> DtcKind.entries.flatMap { session.readDtcs(it) } }
        if (dtcs == null) {
            _uiState.update { it.copy(error = "Нет подключения к адаптеру") }
        } else {
            _uiState.update { it.copy(items = dtcs.map { d -> DtcItem(d, descriptions.describe(d.code)) }, error = null, checked = true) }
        }
    }

    /** Сначала сохранить снимок (после сброса восстановить коды невозможно), потом сбросить и перечитать. */
    fun clear() = launchGuarded {
        history.saveSnapshot(_uiState.value.items.map { it.dtc }, vin = null)
        val cleared = controller.withSession { it.clearDtcs() }
        if (cleared != true) _uiState.update { it.copy(error = "ЭБУ не подтвердил сброс") }
        val dtcs = controller.withSession { session -> DtcKind.entries.flatMap { session.readDtcs(it) } }.orEmpty()
        _uiState.update { it.copy(items = dtcs.map { d -> DtcItem(d, descriptions.describe(d.code)) }, checked = true) }
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

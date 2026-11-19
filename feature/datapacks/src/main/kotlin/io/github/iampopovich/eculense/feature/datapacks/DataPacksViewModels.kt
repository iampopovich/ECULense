package io.github.iampopovich.eculense.feature.datapacks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.datapacks.DataPackService
import io.github.iampopovich.eculense.core.datapacks.PackState
import io.github.iampopovich.eculense.core.obdb.ObdbSignalsets
import io.github.iampopovich.eculense.core.obdb.SignalsetResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the user confirms: download over mobile data or deletion. */
internal sealed interface PackConfirm {
    val pack: PackState

    data class Metered(override val pack: PackState, val version: String?) : PackConfirm
    data class Delete(override val pack: PackState) : PackConfirm
}

internal data class DataPacksUiState(
    val packs: List<PackState> = emptyList(),
    val confirm: PackConfirm? = null,
    val checking: Boolean = false,
    val checkMessage: String? = null,
)

internal class DataPacksViewModel(private val service: DataPackService) : ViewModel() {
    private val local = MutableStateFlow(DataPacksUiState())

    val uiState: StateFlow<DataPacksUiState> = combine(service.observe(), local) { packs, l -> l.copy(packs = packs) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataPacksUiState())

    /** «Скачать»/«Обновить»: on mobile data, first ask with the size (error prevention). */
    fun download(pack: PackState, version: String? = null) {
        if (service.isMeteredNetwork()) {
            local.update { it.copy(confirm = PackConfirm.Metered(pack, version)) }
        } else {
            service.download(pack.manifest.id, version)
        }
    }

    fun update(pack: PackState) = download(pack, pack.updateVersion)

    fun askDelete(pack: PackState) = local.update { it.copy(confirm = PackConfirm.Delete(pack)) }

    fun confirm() {
        when (val c = local.value.confirm) {
            is PackConfirm.Metered -> service.download(c.pack.manifest.id, c.version)
            is PackConfirm.Delete -> service.delete(c.pack.manifest.id)
            null -> Unit
        }
        dismiss()
    }

    fun dismiss() = local.update { it.copy(confirm = null) }

    fun cancel(pack: PackState) = service.cancel(pack.manifest.id)

    fun checkUpdates() {
        if (local.value.checking) return
        viewModelScope.launch {
            local.update { it.copy(checking = true, checkMessage = null) }
            val message = runCatching { service.checkUpdates() }.fold(
                { found -> if (found.isEmpty()) "Обновлений нет" else "Доступны обновления: ${found.size}" },
                { "Не удалось проверить: ${it.message}" },
            )
            local.update { it.copy(checking = false, checkMessage = message) }
        }
    }
}

internal data class SignalsetUiState(
    val carTitle: String? = null,
    val make: String? = null,
    val model: String? = null,
    val carYear: Int? = null,
    /** Year being viewed (defaults to the car's year; can be changed to check a covered year). */
    val year: String = "",
    val loading: Boolean = false,
    val result: SignalsetResult? = null,
    val error: String? = null,
)

internal class SignalsetViewModel(private val cars: CarRepository, private val obdb: ObdbSignalsets) : ViewModel() {
    private val _state = MutableStateFlow(SignalsetUiState())
    val state: StateFlow<SignalsetUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val car = cars.observeActiveCar().first()
            _state.value = SignalsetUiState(
                carTitle = car?.let { listOfNotNull(it.brand, it.model, it.year?.toString()).joinToString(" ").ifBlank { it.name } },
                make = car?.brand, model = car?.model, carYear = car?.year, year = car?.year?.toString().orEmpty(),
            )
            val (make, model, year) = Triple(car?.brand, car?.model, car?.year)
            if (make != null && model != null && year != null) {
                runCatching { obdb.stored(make, model, year) }.getOrNull()?.let { stored -> _state.update { it.copy(result = stored) } }
            }
        }
    }

    fun setYear(value: String) = _state.update { it.copy(year = value.filter(Char::isDigit).take(4)) }

    fun check() {
        val s = _state.value
        if (s.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val result = runCatching { obdb.check(s.make, s.model, s.year.toIntOrNull()) }
            _state.update {
                it.copy(loading = false, result = result.getOrNull() ?: it.result, error = result.exceptionOrNull()?.let { e -> "Нет связи с OBDb: ${e.message}" })
            }
        }
    }
}

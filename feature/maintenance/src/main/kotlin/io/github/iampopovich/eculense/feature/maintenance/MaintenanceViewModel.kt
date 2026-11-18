package io.github.iampopovich.eculense.feature.maintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import io.github.iampopovich.eculense.core.analytics.MaintenanceRule
import io.github.iampopovich.eculense.core.analytics.OdometerEstimate
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.MaintenanceRepository
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import io.github.iampopovich.eculense.core.model.OdometerEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Что можно отменить через снекбар: новую запись пробега, отметку «сделано», удаление работы. */
internal sealed interface Undo {
    val text: String
    data class Odometer(val entryId: Long, override val text: String) : Undo
    data class Item(val previous: MaintenanceItem, override val text: String) : Undo
    data class Deleted(val item: MaintenanceItem, override val text: String) : Undo
}

internal data class MaintenanceUiState(
    val loading: Boolean = true,
    val car: CarProfile? = null,
    val estimate: OdometerEstimate? = null,
    val odometerLog: List<OdometerEntry> = emptyList(),
    val items: List<MaintenanceDue> = emptyList(),
    val undo: Undo? = null,
    val nowMs: Long = 0,
)

/** «Обслуживание» активной машины (3.9 п.2): список работ со статусами, ввод пробега, «Сделано» с отменой. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class MaintenanceViewModel(
    private val cars: CarRepository,
    private val repo: MaintenanceRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val undo = MutableStateFlow<Undo?>(null)
    private val activeCar = cars.observeActiveCar().distinctUntilChanged()

    private val carData = activeCar.map { it?.id }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf(Triple(emptyList(), null, emptyList()))
        else combine(repo.observeItems(id), repo.observeEstimate(id), repo.observeOdometer(id)) { items, est, log ->
            Triple<List<MaintenanceItem>, OdometerEstimate?, List<OdometerEntry>>(items, est, log)
        }
    }

    val uiState: StateFlow<MaintenanceUiState> = combine(activeCar, carData, undo) { car, (items, est, log), u ->
        val now = clock()
        MaintenanceUiState(
            loading = false, car = car, estimate = est, odometerLog = log,
            items = MaintenanceRule.evaluateAll(items, est?.km, now, zone), undo = u, nowMs = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MaintenanceUiState())

    init {
        // Первый вход для машины — типовой список работ (один раз; удалённое не возвращается).
        viewModelScope.launch { cars.observeActiveCar().first()?.let { repo.ensureTemplate(it) } }
    }

    fun addOdometer(km: Long) = launchForCar { car ->
        val id = repo.addOdometer(car.id, km, clock())
        undo.value = Undo.Odometer(id, "Пробег сохранён: ${MaintenanceFormat.km(km)}")
    }

    fun deleteOdometer(entryId: Long) = viewModelScope.launch { repo.deleteOdometer(entryId) }

    fun markDone(itemId: Long, km: Long?, atMs: Long) = viewModelScope.launch {
        val previous = repo.markDone(itemId, km, atMs) ?: return@launch
        undo.value = Undo.Item(previous, "Отмечено: ${previous.title}")
    }

    fun save(item: MaintenanceItem) = launchForCar { car -> repo.save(item.copy(carId = car.id)) }

    fun delete(item: MaintenanceItem) = viewModelScope.launch {
        repo.delete(item.id)
        undo.value = Undo.Deleted(item, "Удалено: ${item.title}")
    }

    fun undo() {
        val u = undo.value ?: return
        undo.value = null
        viewModelScope.launch {
            when (u) {
                is Undo.Odometer -> repo.deleteOdometer(u.entryId)
                is Undo.Item -> repo.save(u.previous)
                is Undo.Deleted -> repo.save(u.item.copy(id = 0))
            }
        }
    }

    fun dismissUndo() {
        undo.value = null
    }

    fun now(): Long = clock()

    private fun launchForCar(block: suspend (CarProfile) -> Unit) = viewModelScope.launch {
        val car = cars.observeActiveCar().first() ?: return@launch
        block(car)
    }
}

package io.github.iampopovich.eculense.feature.car

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.UnitsRepository
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.UnitsSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class CarUiState(
    val loading: Boolean = true,
    val cars: List<CarProfile> = emptyList(),
    val active: CarProfile? = null,
    val units: UnitsSettings = UnitsSettings(),
)

/** Car list, active car and units. Statistics is a separate tab (:feature:stats). */
internal class CarViewModel(
    private val cars: CarRepository,
    private val unitsRepository: UnitsRepository,
) : ViewModel() {
    val uiState: StateFlow<CarUiState> =
        combine(cars.observeCars(), cars.observeActiveCar(), unitsRepository.units) { all, active, units ->
            CarUiState(loading = false, cars = all, active = active, units = units)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CarUiState())

    fun setActive(id: Long) {
        viewModelScope.launch { cars.setActive(id) }
    }

    fun setUnits(units: UnitsSettings) {
        viewModelScope.launch { unitsRepository.save(units) }
    }
}

package io.github.iampopovich.eculense.feature.car

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.TripRepository
import io.github.iampopovich.eculense.core.data.UnitsRepository
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.StatsPeriod
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripStatistics
import io.github.iampopovich.eculense.core.model.TripStats
import io.github.iampopovich.eculense.core.model.UnitsSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId

internal data class CarUiState(
    val loading: Boolean = true,
    val cars: List<CarProfile> = emptyList(),
    val active: CarProfile? = null,
    val period: StatsPeriod = StatsPeriod.ALL,
    val stats: TripStats = TripStats.EMPTY,
    val months: List<Pair<YearMonth, TripStats>> = emptyList(),
    val totalTrips: Int = 0,
    val units: UnitsSettings = UnitsSettings(),
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class CarViewModel(
    private val cars: CarRepository,
    trips: TripRepository,
    private val unitsRepository: UnitsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val period = MutableStateFlow(StatsPeriod.ALL)

    private val activeTrips = cars.observeActiveCar().flatMapLatest { car ->
        if (car == null) flowOf<Pair<CarProfile?, List<Trip>>>(null to emptyList())
        else trips.observeTrips(car.id).map<List<Trip>, Pair<CarProfile?, List<Trip>>> { car to it }
    }

    val uiState: StateFlow<CarUiState> =
        combine(cars.observeCars(), activeTrips, period, unitsRepository.units) { all, (active, list), p, units ->
            CarUiState(
                loading = false,
                cars = all,
                active = active,
                period = p,
                stats = TripStatistics.forPeriod(list, p, clock()),
                months = TripStatistics.byMonth(list, zone),
                totalTrips = list.size,
                units = units,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CarUiState())

    fun selectPeriod(p: StatsPeriod) {
        period.value = p
    }

    fun setActive(id: Long) {
        viewModelScope.launch { cars.setActive(id) }
    }

    fun setUnits(units: UnitsSettings) {
        viewModelScope.launch { unitsRepository.save(units) }
    }
}

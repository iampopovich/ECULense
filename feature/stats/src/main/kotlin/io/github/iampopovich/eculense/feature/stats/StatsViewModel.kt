package io.github.iampopovich.eculense.feature.stats

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

internal data class StatsUiState(
    val loading: Boolean = true,
    val car: CarProfile? = null,
    val period: StatsPeriod = StatsPeriod.ALL,
    val stats: TripStats = TripStats.EMPTY,
    /** Trips in the selected period, newest first. */
    val trips: List<Trip> = emptyList(),
    /** Total trips for the car (for the empty state: "none at all" ≠ "none in the period"). */
    val totalTrips: Int = 0,
    val units: UnitsSettings = UnitsSettings(),
)

/** Stats for the active car. Data comes only from :core:data (trips, cars, units). */
@OptIn(ExperimentalCoroutinesApi::class)
internal class StatsViewModel(
    cars: CarRepository,
    trips: TripRepository,
    units: UnitsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val period = MutableStateFlow(StatsPeriod.ALL)

    private val activeTrips = cars.observeActiveCar().flatMapLatest { car ->
        if (car == null) flowOf<Pair<CarProfile?, List<Trip>>>(null to emptyList())
        else trips.observeTrips(car.id).map<List<Trip>, Pair<CarProfile?, List<Trip>>> { car to it }
    }

    val uiState: StateFlow<StatsUiState> =
        combine(activeTrips, period, units.units) { (car, all), p, u ->
            val inPeriod = TripStatistics.inPeriod(all, p, clock()).sortedByDescending { it.startedAtMs }
            StatsUiState(
                loading = false,
                car = car,
                period = p,
                stats = TripStats.of(inPeriod),
                trips = inPeriod,
                totalTrips = all.size,
                units = u,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun selectPeriod(p: StatsPeriod) {
        period.value = p
    }
}

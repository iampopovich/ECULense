package io.github.iampopovich.eculense.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.analytics.FuelPeriodSummary
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.FuelInsights
import io.github.iampopovich.eculense.core.data.FuelPrefs
import io.github.iampopovich.eculense.core.data.FuelRepository
import io.github.iampopovich.eculense.core.data.TripRepository
import io.github.iampopovich.eculense.core.data.UnitsRepository
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.StatsPeriod
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripStatistics
import io.github.iampopovich.eculense.core.model.TripStats
import io.github.iampopovich.eculense.core.model.UnitsSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** D1: where the consumption and the fuel money of the period come from. */
internal enum class FuelSource {
    /** Full-to-full intervals / receipts of the fuel log in this period. */
    FILL_UPS,
    /** The trips' own estimates (MAF / 01.5E / Car Scanner). */
    TRIPS,
    /** Trip estimates multiplied by the calibration factor from the fuel log. */
    TRIPS_CORRECTED,
}

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
    /** Consumption shown in the summary and where it comes from. */
    val consumption: Double? = null,
    val consumptionSource: FuelSource = FuelSource.TRIPS,
    /** Fuel money of the period and where it comes from. */
    val fuelCost: Double = 0.0,
    val costSource: FuelSource = FuelSource.TRIPS,
)

/** Stats for the active car. Data comes only from :core:data (trips, cars, units, fuel log). */
@OptIn(ExperimentalCoroutinesApi::class)
internal class StatsViewModel(
    cars: CarRepository,
    trips: TripRepository,
    units: UnitsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    fuel: FuelRepository? = null,
    fuelPrefs: FuelPrefs? = null,
) : ViewModel() {
    private val period = MutableStateFlow(StatsPeriod.ALL)

    private data class CarData(val car: CarProfile?, val trips: List<Trip>, val insights: FuelInsights?)

    private val active: Flow<CarData> = cars.observeActiveCar().flatMapLatest { car ->
        when {
            car == null -> flowOf(CarData(null, emptyList(), null))
            fuel == null || fuelPrefs == null -> trips.observeTrips(car.id).map { CarData(car, it, null) }
            else -> combine(trips.observeTrips(car.id), fuel.observeFillUps(car.id), fuelPrefs.mafCorrection(car.id)) { t, f, on ->
                CarData(car, t, FuelInsights.of(f, t, on))
            }
        }
    }

    val uiState: StateFlow<StatsUiState> =
        combine(active, period, units.units) { d, p, u -> build(d, p, u) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    private fun build(d: CarData, p: StatsPeriod, u: UnitsSettings): StatsUiState {
        val now = clock()
        val insights = d.insights
        val corrected = insights?.corrected(d.trips) ?: d.trips
        val inPeriod = TripStatistics.inPeriod(corrected, p, now).sortedByDescending { it.startedAtMs }
        val stats = TripStats.of(inPeriod)
        val from = p.days?.let { now - it * DAY_MS } ?: Long.MIN_VALUE
        val log = insights?.let { FuelPeriodSummary.of(it.fillUps, it.intervals, from, now) }
        val estimateSource = if (insights != null && insights.appliedFactor != 1.0) FuelSource.TRIPS_CORRECTED else FuelSource.TRIPS
        val fromLog = log?.lPer100Km
        val logSpend = log?.takeIf { it.fillUps > 0 }?.spend
        return StatsUiState(
            loading = false,
            car = d.car,
            period = p,
            stats = stats,
            trips = inPeriod,
            totalTrips = d.trips.size,
            units = u,
            consumption = fromLog ?: stats.avgLPer100Km,
            consumptionSource = if (fromLog != null) FuelSource.FILL_UPS else estimateSource,
            fuelCost = logSpend ?: stats.fuelCost,
            costSource = if (logSpend != null) FuelSource.FILL_UPS else estimateSource,
        )
    }

    fun selectPeriod(p: StatsPeriod) {
        period.value = p
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}

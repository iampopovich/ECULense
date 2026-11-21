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
import io.github.iampopovich.eculense.core.analytics.StatsTrends
import io.github.iampopovich.eculense.core.analytics.StyleTrendPoint
import io.github.iampopovich.eculense.core.analytics.AmbientSource
import io.github.iampopovich.eculense.core.analytics.TemperatureBucket
import io.github.iampopovich.eculense.core.analytics.TrendBucket
import io.github.iampopovich.eculense.core.analytics.TripFacts
import io.github.iampopovich.eculense.core.data.TripFactsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.ZoneId
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
    /** R2.8: median style score per week (month/quarter period) or month (longer); empty = card hidden. */
    val styleTrend: List<StyleTrendPoint> = emptyList(),
    val styleBucket: TrendBucket = TrendBucket.MONTH,
    /** R2.8: consumption per 5 °C of outside temperature; empty = card hidden. */
    val temperature: List<TemperatureBucket> = emptyList(),
    /** Where the card's temperatures come from: PID 46 for every trip, intake air for every trip, or both. */
    val temperatureSource: TemperatureSource = TemperatureSource.OUTSIDE,
)

/** Source of the consumption-vs-temperature card: the title says honestly what was measured. */
internal enum class TemperatureSource { OUTSIDE, INTAKE_ESTIMATE, MIXED }

/** Stats for the active car. Data comes only from :core:data (trips, cars, units, fuel log). */
@OptIn(ExperimentalCoroutinesApi::class)
internal class StatsViewModel(
    cars: CarRepository,
    trips: TripRepository,
    units: UnitsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    fuel: FuelRepository? = null,
    fuelPrefs: FuelPrefs? = null,
    private val facts: TripFactsService? = null,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    private val period = MutableStateFlow(StatsPeriod.ALL)

    /** Cached per-trip facts; filled in the background from the recordings once per trip. */
    private val factsMap = MutableStateFlow<Map<Long, TripFacts>>(emptyMap())

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
        combine(active.onEach(::refreshFacts), period, units.units, factsMap) { d, p, u, f -> build(d, p, u, f) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    private var factsJob: Job? = null

    /** Shows the cached facts at once, then computes the missing ones off the main thread (progressively). */
    private fun refreshFacts(d: CarData) {
        val service = facts ?: return
        factsJob?.cancel()
        factsJob = viewModelScope.launch(Dispatchers.IO) {
            factsMap.value = runCatching { service.cached() }.getOrDefault(emptyMap())
            runCatching { service.ensure(d.trips) { factsMap.value = it } }
        }
    }

    private fun build(d: CarData, p: StatsPeriod, u: UnitsSettings, f: Map<Long, TripFacts> = emptyMap()): StatsUiState {
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
        val tempTrips = inPeriod.filter { it.fuelKnown && it.fuelL > 0 && it.distanceKm >= StatsTrends.MIN_TRIP_KM }
            .mapNotNull { t -> f[t.id]?.let { fact -> fact.ambientC?.let { c -> (t.fuelL / t.distanceKm * 100 to c) to fact.ambientSource } } }
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
            styleTrend = StatsTrends.styleTrend(inPeriod.mapNotNull { t -> f[t.id]?.styleScore?.let { t.startedAtMs to it } }, bucketOf(p), zone()),
            styleBucket = bucketOf(p),
            temperature = StatsTrends.consumptionByTemperature(tempTrips.map { it.first }),
            temperatureSource = temperatureSource(tempTrips.map { it.second }),
        )
    }

    private fun temperatureSource(sources: List<AmbientSource?>): TemperatureSource {
        val intake = sources.count { it == AmbientSource.INTAKE_AIR }
        return when {
            intake == 0 -> TemperatureSource.OUTSIDE
            intake == sources.size -> TemperatureSource.INTAKE_ESTIMATE
            else -> TemperatureSource.MIXED
        }
    }

    /** Weeks inside a month or quarter, months for longer periods. */
    private fun bucketOf(p: StatsPeriod): TrendBucket = if ((p.days ?: Long.MAX_VALUE) <= 92L) TrendBucket.WEEK else TrendBucket.MONTH

    fun selectPeriod(p: StatsPeriod) {
        period.value = p
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}

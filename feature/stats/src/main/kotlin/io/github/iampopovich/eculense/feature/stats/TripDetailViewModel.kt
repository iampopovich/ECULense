package io.github.iampopovich.eculense.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.analytics.DrivingStyle
import io.github.iampopovich.eculense.core.analytics.DrivingStyleRules
import io.github.iampopovich.eculense.core.analytics.SensorIds
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.SessionRepository
import io.github.iampopovich.eculense.core.data.TripEvent
import io.github.iampopovich.eculense.core.data.TripEventRepository
import io.github.iampopovich.eculense.core.data.TripRepository
import io.github.iampopovich.eculense.core.data.UnitsRepository
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.UnitsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A chart line: seconds from the trip start → value in stored (metric) units. */
internal data class Series(val sensorId: String, val x: List<Double>, val y: List<Double>)

internal data class TripDetailUiState(
    val loading: Boolean = true,
    val trip: Trip? = null,
    val car: CarProfile? = null,
    val units: UnitsSettings = UnitsSettings(),
    /** The recording behind the trip (own or an overlapping import); null = totals only (e.g. a Car Scanner trip). */
    val sessionId: Long? = null,
    val maxSpeedKmh: Double? = null,
    val series: List<Series> = emptyList(),
    val style: DrivingStyle? = null,
    val events: List<TripEvent> = emptyList(),
) {
    /** Average moving speed: distance over the time spent moving. */
    val avgSpeedKmh: Double? get() = trip?.takeIf { it.drivingMs > 0 }?.let { it.distanceKm / (it.drivingMs / 3_600_000.0) }
    val cost: Double? get() = trip?.let { t -> t.fuelPricePerL?.takeIf { t.fuelKnown && t.fuelL > 0 }?.let { it * t.fuelL } }
}

/** D4 trip page: totals, speed/rpm/coolant charts, harsh events, style score (D6) and the recording's events. Read-only. */
internal class TripDetailViewModel(
    private val tripId: Long,
    private val trips: TripRepository,
    private val cars: CarRepository,
    private val units: UnitsRepository,
    private val sessions: SessionRepository?,
    private val events: TripEventRepository?,
) : ViewModel() {
    private val _state = MutableStateFlow(TripDetailUiState())
    val state: StateFlow<TripDetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val trip = trips.trip(tripId)
        val car = trip?.let { t -> cars.cars().firstOrNull { it.id == t.carId } }
        val u = units.units.first()
        if (trip == null) {
            _state.value = TripDetailUiState(loading = false, units = u)
            return
        }
        val sessionId = runCatching { trips.sessionOf(trip) }.getOrNull()
        val repo = sessions
        val readings = if (sessionId != null && repo != null) runCatching { repo.readings(sessionId) }.getOrDefault(emptyList()) else emptyList()
        val (series, style, max) = withContext(Dispatchers.Default) { analyse(readings, trip) }
        val ev = sessionId?.let { id -> runCatching { events?.forSession(id) }.getOrNull() }.orEmpty()
        _state.value = TripDetailUiState(false, trip, car, u, sessionId, max, series, style, ev)
    }

    companion object {
        val CHARTED = listOf(SensorIds.SPEED, SensorIds.RPM, SensorIds.COOLANT)
        const val MAX_POINTS = 300

        /** Only the trip's time span of the recording (an overlapping import can be longer than the trip). */
        internal fun analyse(readings: List<SensorReading>, trip: Trip): Triple<List<Series>, DrivingStyle?, Double?> {
            val inTrip = readings.filter { it.timestampMs in trip.startedAtMs..trip.endedAtMs }.ifEmpty { readings }
            if (inTrip.isEmpty()) return Triple(emptyList(), null, null)
            val t0 = inTrip.minOf { it.timestampMs }
            val series = CHARTED.mapNotNull { id ->
                val pts = inTrip.filter { it.sensorId == id }.sortedBy { it.timestampMs }
                if (pts.size < 2) return@mapNotNull null
                val step = maxOf(1, pts.size / MAX_POINTS)
                val thin = pts.filterIndexed { i, _ -> i % step == 0 }
                Series(id, thin.map { (it.timestampMs - t0) / 1000.0 }, thin.map { it.value })
            }
            val style = DrivingStyleRules.evaluate(
                inTrip.filter { it.sensorId == SensorIds.SPEED || it.sensorId == SensorIds.RPM }.sortedBy { it.timestampMs }
                    .map { Triple(it.sensorId, it.value, it.timestampMs) },
            )
            val max = inTrip.filter { it.sensorId == SensorIds.SPEED }.maxOfOrNull { it.value }
            return Triple(series, style, max)
        }
    }
}

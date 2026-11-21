package io.github.iampopovich.eculense.feature.stats

import io.github.iampopovich.eculense.core.data.ExportFiles
import io.github.iampopovich.eculense.core.model.attempt
import io.github.iampopovich.eculense.core.model.AppLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.analytics.DrivingStyle
import io.github.iampopovich.eculense.core.analytics.DrivingStyleRules
import io.github.iampopovich.eculense.core.analytics.SensorIds
import io.github.iampopovich.eculense.core.analytics.DriverThreshold
import io.github.iampopovich.eculense.core.analytics.StyleThresholds
import io.github.iampopovich.eculense.core.data.AccelCalibrations
import io.github.iampopovich.eculense.core.data.CalibrationReport
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.StylePrefs
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
import java.io.File

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
    /** E5-lite card; null = the recording has no accelerometer file. */
    val calibration: CalibrationUi? = null,
) {
    /** Average moving speed: distance over the time spent moving. */
    val avgSpeedKmh: Double? get() = trip?.takeIf { it.drivingMs > 0 }?.let { it.distanceKm / (it.drivingMs / 3_600_000.0) }
    val cost: Double? get() = trip?.let { t -> t.fuelPricePerL?.takeIf { t.fuelKnown && t.fuelL > 0 }?.let { it * t.fuelL } }
}

/** E5-lite «Threshold calibration» card: the recording's sensor calibration against the threshold in use. */
internal data class CalibrationUi(
    val report: CalibrationReport,
    /** μ + 2σ over the car's recordings; null while loading or with too few windows. */
    val driver: DriverThreshold? = null,
    val driverLoading: Boolean = true,
    /** Harsh threshold in use for this car, m/s². */
    val currentMs2: Double = StyleThresholds.HARSH_ACCEL_MS2,
    val overridden: Boolean = false,
) {
    val canApply: Boolean get() = report.calibration.recommendedMs2 != null
}

/** D4 trip page: totals, speed/rpm/coolant charts, harsh events, style score (D6) and the recording's events. Read-only. */
internal class TripDetailViewModel(
    private val tripId: Long,
    private val trips: TripRepository,
    private val cars: CarRepository,
    private val units: UnitsRepository,
    private val sessions: SessionRepository?,
    private val events: TripEventRepository?,
    private val calibrations: AccelCalibrations? = null,
    private val stylePrefs: StylePrefs? = null,
    /** cache/exports for the CSV share (FileProvider path). */
    private val exportDir: File? = null,
    private val logger: AppLogger = AppLogger.NONE,
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
        val override = stylePrefs?.let { p -> runCatching { p.harshMs2(trip.carId).first() }.getOrNull() }
        val harsh = override ?: StyleThresholds.HARSH_ACCEL_MS2
        val (series, style, max) = withContext(Dispatchers.Default) { analyse(readings, trip, harsh) }
        val ev = sessionId?.let { id -> runCatching { events?.forSession(id) }.getOrNull() }.orEmpty()
        val cal = calibrations
        val report = if (sessionId != null && cal != null && cal.exists(sessionId)) {
            withContext(Dispatchers.Default) { runCatching { cal.report(sessionId, trip.startedAtMs, trip.endedAtMs) }.getOrNull() }
        } else {
            null
        }
        val previous = _state.value.calibration
        _state.value = TripDetailUiState(
            false, trip, car, u, sessionId, max, series, style, ev,
            calibration = report?.let {
                CalibrationUi(it, previous?.driver, previous?.driverLoading ?: true, harsh, override != null)
            },
        )
        if (report != null && cal != null && previous?.driverLoading != false) {
            val driver = withContext(Dispatchers.Default) { runCatching { cal.driverThreshold(trip.carId) }.getOrNull() }
            _state.value = _state.value.let { s -> s.copy(calibration = s.calibration?.copy(driver = driver, driverLoading = false)) }
        }
    }

    /** «Apply for this car»: the recommended threshold becomes this car's D6 harsh level. */
    fun applyCalibration() {
        val s = _state.value
        val carId = s.trip?.carId ?: return
        val ms2 = s.calibration?.report?.calibration?.recommendedMs2 ?: return
        val prefs = stylePrefs ?: return
        viewModelScope.launch {
            prefs.setHarshMs2(carId, ms2)
            load()
        }
    }

    /** «Reset»: back to the sourced default (Geotab 0.3 g). */
    fun resetCalibration() {
        val carId = _state.value.trip?.carId ?: return
        val prefs = stylePrefs ?: return
        viewModelScope.launch {
            prefs.setHarshMs2(carId, null)
            load()
        }
    }

    /** Writes the 10 Hz CSV to cache/exports; null without a file. */
    suspend fun exportCsv(): File? {
        val id = _state.value.sessionId ?: return null
        val cal = calibrations ?: return null
        val dir = exportDir ?: return null
        return withContext(Dispatchers.IO) {
            val f = ExportFiles.fresh(dir, "ecu-accel-$id.csv")
            logger.attempt("EcuStats", "accelerometer CSV export of recording $id") { cal.writeCsv(id, f) }
                .getOrNull()?.takeIf { it > 0 }?.let { f }
        }
    }

    companion object {
        val CHARTED = listOf(SensorIds.SPEED, SensorIds.RPM, SensorIds.COOLANT)
        const val MAX_POINTS = 300

        /** Only the trip's time span of the recording (an overlapping import can be longer than the trip). */
        internal fun analyse(
            readings: List<SensorReading>,
            trip: Trip,
            harshMs2: Double = StyleThresholds.HARSH_ACCEL_MS2,
        ): Triple<List<Series>, DrivingStyle?, Double?> {
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
                harshMs2,
            )
            val max = inTrip.filter { it.sensorId == SensorIds.SPEED }.maxOfOrNull { it.value }
            return Triple(series, style, max)
        }
    }
}

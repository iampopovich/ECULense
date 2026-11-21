package io.github.iampopovich.eculense.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.iampopovich.eculense.core.database.AnalyticsDao
import io.github.iampopovich.eculense.core.database.TripDao
import io.github.iampopovich.eculense.core.obd.AdapterPrefs
import io.github.iampopovich.eculense.core.obd.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.scope.Scope
import io.github.iampopovich.eculense.core.model.AppLogger
import io.github.iampopovich.eculense.core.model.attempt
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Reaction to new data (recording stopped, import finished). Implemented by the optional background-work module
 * (:core:background: analysis in WorkManager and notifications). Without it, the data layer recomputes findings itself.
 */
interface DataEvents {
    fun recordingFinished(sessionId: Long)
    fun importFinished()
}

val dataModule = module {
    single<LiveWriteGate> { LiveWriteGate.of(get()) }
    single { DataStoreVehiclePrefs(PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("vehicle") }) } binds
        arrayOf(VehiclePrefs::class, UnitsRepository::class, MaintenancePrefs::class, FuelPrefs::class)
    single<FuelRepository> { RoomFuelRepository(get(), get(), get()) }
    single<ExpenseRepository> { RoomExpenseRepository(get(), get()) }
    single<CarRepository> { RoomCarRepository(get(), get(), get()) }
    single<AdapterPrefs> {
        val cars = get<CarRepository>()
        DataStoreAdapterPrefs(PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("adapter") }) { cars.activeCarId() }
    }
    single<TripRepository> { RoomTripRepository(get()) }
    single<DashboardPrefs> {
        val vehicle = get<DataStoreVehiclePrefs>()
        val cars = get<CarRepository>()
        DataStoreDashboardPrefs(
            PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("dashboard") },
            vehicle.activeCarId,
            cars.observeActiveCar().map { it?.fuelType },
        )
    }
    single<MaintenanceRepository> { RoomMaintenanceRepository(get(), get(), get(), get()) }
    single {
        val tripDao = get<TripDao>()
        val analyticsDao = get<AnalyticsDao>()
        RecordingTrips(candidates = { tripDao.sessionsWithoutTrip() }, points = { id, sensors -> analyticsDao.points(id, sensors) }, get(), get())
    }
    single { AccelFiles(java.io.File(androidContext().filesDir, "accel")) }
    single<SessionRepository> {
        val cars = get<CarRepository>()
        val accel = get<AccelFiles>()
        RoomSessionRepository(get(), activeCarId = { cars.activeCarId() }, onDeleted = { accel.delete(it) }, logger = logger())
    }
    single<StylePrefs> { DataStoreStylePrefs(PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("style") }) }
    single<TripFactsStore> { DataStoreTripFactsStore(PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("trip_facts") }) }
    single {
        val analyticsDao = get<AnalyticsDao>()
        val style = get<StylePrefs>()
        TripFactsService(
            get(),
            points = { id, sensors -> analyticsDao.points(id, sensors).map { Triple(it.sensorId, it.value, it.timestampMs) } },
            store = get(),
            harshMs2 = { car -> style.harshMs2(car).first() },
        )
    }
    single {
        val analyticsDao = get<AnalyticsDao>()
        val trips = get<TripRepository>()
        AccelCalibrations(
            get(),
            speed = { id, from, to ->
                analyticsDao.points(id, listOf(io.github.iampopovich.eculense.core.analytics.SensorIds.SPEED))
                    .filter { it.timestampMs in from..to }
                    .map { io.github.iampopovich.eculense.core.analytics.SpeedSample(it.timestampMs, it.value) }
            },
            carSessions = { carId -> trips.trips(carId).sortedByDescending { it.startedAtMs }.mapNotNull { runCatching { trips.sessionOf(it) }.getOrNull() }.distinct() },
        )
    }
    single<DtcRepository> {
        val cars = get<CarRepository>()
        val lastSeen = get<LastSeenStore>()
        RoomDtcRepository(
            get(), activeCarId = { cars.activeCarId() }, liveWrites = get(),
            onCheck = { count, at -> cars.activeCarId()?.let { lastSeen.saveDtcCheck(it, count, at) } },
        )
    }
    // C9: last voltage and code check per car for the home-screen widget (real links only).
    single<LastSeenStore> { DataStoreLastSeen(PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("last_seen") }) }
    single(createdAtStart = true) {
        val cars = get<CarRepository>()
        LastSeenRecorder(get(), get(), activeCarId = { cars.activeCarId() }, liveWrites = get(), scope = get(AppScope)).also { it.start() }
    }
    single<SessionRecorder> {
        val trips = get<RecordingTrips>()
        val koin = getKoin()
        val scope = get<CoroutineScope>(AppScope)
        // Trip from the recording right away (fast, single recording); analysis and notifications in the background module.
        val logger = logger()
        LiveSessionRecorder(get(), get(), scope, logger = logger, onFinished = { id ->
            logger.attempt(TAG, "trip backfill after recording $id") { trips.backfill() }
            val events = koin.dataEvents()
            if (events.isEmpty()) recomputeAll(koin, scope) else events.forEach { it.recordingFinished(id) }
        })
    }
    single<AnalyticsRepository> {
        val events = get<io.github.iampopovich.eculense.core.database.EventDao>()
        RoomAnalyticsRepository(get(), get(), get(), get(), tripEvents = { carId -> events.forCar(carId, 0, Long.MAX_VALUE).mapNotNull { it.toModel()?.toFact() } })
    }
    single<TripEventRepository> { RoomTripEventRepository(get()) }
    single(createdAtStart = true) {
        val moves = get<io.github.iampopovich.eculense.core.database.SessionMoveDao>()
        LiveEventRecorder(get(), get(), get(), get(), get(AppScope), carOf = { moves.carOf(it) }, logger = logger()).also { it.start() }
    } binds arrayOf(EventMarker::class)
    single<SessionMover> {
        val analytics = get<AnalyticsRepository>()
        RoomSessionMover(get(), get(AppScope)) { analytics.recompute(it) }
    }
    single<HealthRepository> { RoomHealthRepository(get()) }
    single {
        val cars = get<CarRepository>()
        val maintenance = get<MaintenanceRepository>()
        HealthCheckRunner(get(), get(), get(), activeCarId = { cars.activeCarId() }, odometerKm = { maintenance.estimate(it)?.km?.toLong() })
    }
    single<AlertPrefs> { DataStoreAlertPrefs(PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("alerts") }) }
    single(createdAtStart = true) {
        val koin = getKoin()
        LiveAlertMonitor(get(), get(), get(AppScope), notifier = { koin.getOrNull<TripAlertNotifier>() }, logger = logger()).also { it.start() }
    } binds arrayOf(LiveAlerts::class)
    single<AutomationPrefs> { DataStoreAutomationPrefs(PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("automation") }) }
    single(createdAtStart = true) {
        val context = androidContext()
        AutoRecorder(get(), get(), get(), get(AppScope), sessionName = { stamp -> context.getString(R.string.data_auto_session_name, stamp) })
            .also { it.start() }
    }
    single(createdAtStart = true) { AutoConnect(get(), get(), get(AppScope)).also { it.start() } }
    single { RoomImportLedger(get()) } binds arrayOf(ImportLedger::class, ImportHistoryRepository::class)
    single {
        val scope = get<CoroutineScope>(AppScope)
        val koin = getKoin()
        val trips = get<RecordingTrips>()
        val logger = logger()
        // New recordings and codes → trips for recordings without a Car Scanner trip, then findings (in background, for all cars).
        CarScannerImporter(androidContext(), get(), get(), get(), get(), get(), get(), getOrNull<AdapterPrefs>()) { report ->
            if (report.imported > 0 || report.tripsImported > 0 || report.tripsUpdated > 0 || report.dtcEventsAdded > 0) {
                scope.launch {
                    logger.attempt(TAG, "trip backfill after import") { trips.backfill() }
                    val events = koin.dataEvents()
                    if (events.isEmpty()) recomputeAll(koin, scope) else events.forEach { it.importFinished() }
                }
            }
        }
    }
}

private const val TAG = "EcuData"

/** The app's logger, or a silent one in tests without it. */
internal fun Scope.logger(): AppLogger = getOrNull<AppLogger>() ?: AppLogger.NONE

private fun Koin.dataEvents(): List<DataEvents> = runCatching { getAll<DataEvents>() }.getOrDefault(emptyList())

private fun recomputeAll(koin: Koin, scope: CoroutineScope) {
    val analytics = koin.get<AnalyticsRepository>()
    val cars = koin.get<CarRepository>()
    scope.launch { cars.cars().forEach { runCatching { analytics.recompute(it.id) } } }
}

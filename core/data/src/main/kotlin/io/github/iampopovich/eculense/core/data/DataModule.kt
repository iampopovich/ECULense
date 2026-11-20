package io.github.iampopovich.eculense.core.data

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
        arrayOf(VehiclePrefs::class, UnitsRepository::class, MaintenancePrefs::class)
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
    single<SessionRepository> {
        val cars = get<CarRepository>()
        RoomSessionRepository(get(), activeCarId = { cars.activeCarId() })
    }
    single<DtcRepository> {
        val cars = get<CarRepository>()
        RoomDtcRepository(get(), activeCarId = { cars.activeCarId() }, liveWrites = get())
    }
    single<SessionRecorder> {
        val trips = get<RecordingTrips>()
        val koin = getKoin()
        val scope = get<CoroutineScope>(AppScope)
        // Trip from the recording right away (fast, single recording); analysis and notifications in the background module.
        LiveSessionRecorder(get(), get(), scope) { id ->
            runCatching { trips.backfill() }
            val events = koin.dataEvents()
            if (events.isEmpty()) recomputeAll(koin, scope) else events.forEach { it.recordingFinished(id) }
        }
    }
    single<AnalyticsRepository> {
        val events = get<io.github.iampopovich.eculense.core.database.EventDao>()
        RoomAnalyticsRepository(get(), get(), get(), get(), tripEvents = { carId -> events.forCar(carId, 0, Long.MAX_VALUE).mapNotNull { it.toModel()?.toFact() } })
    }
    single<TripEventRepository> { RoomTripEventRepository(get()) }
    single(createdAtStart = true) {
        val moves = get<io.github.iampopovich.eculense.core.database.SessionMoveDao>()
        LiveEventRecorder(get(), get(), get(), get(), get(AppScope), carOf = { moves.carOf(it) }).also { it.start() }
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
        LiveAlertMonitor(get(), get(), get(AppScope), notifier = { koin.getOrNull<TripAlertNotifier>() }).also { it.start() }
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
        // New recordings and codes → trips for recordings without a Car Scanner trip, then findings (in background, for all cars).
        CarScannerImporter(androidContext(), get(), get(), get(), get(), get(), get()) { report ->
            if (report.imported > 0 || report.tripsImported > 0 || report.tripsUpdated > 0 || report.dtcEventsAdded > 0) {
                scope.launch {
                    runCatching { trips.backfill() }
                    val events = koin.dataEvents()
                    if (events.isEmpty()) recomputeAll(koin, scope) else events.forEach { it.importFinished() }
                }
            }
        }
    }
}

private fun Koin.dataEvents(): List<DataEvents> = runCatching { getAll<DataEvents>() }.getOrDefault(emptyList())

private fun recomputeAll(koin: Koin, scope: CoroutineScope) {
    val analytics = koin.get<AnalyticsRepository>()
    val cars = koin.get<CarRepository>()
    scope.launch { cars.cars().forEach { runCatching { analytics.recompute(it.id) } } }
}

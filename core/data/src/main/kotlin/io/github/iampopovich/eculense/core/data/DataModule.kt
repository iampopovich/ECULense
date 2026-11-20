package io.github.iampopovich.eculense.core.data

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
    single { DataStoreVehiclePrefs(PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("vehicle") }) } binds
        arrayOf(VehiclePrefs::class, UnitsRepository::class, MaintenancePrefs::class)
    single<CarRepository> { RoomCarRepository(get(), get(), get()) }
    single<AdapterPrefs> {
        val cars = get<CarRepository>()
        DataStoreAdapterPrefs(PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("adapter") }) { cars.activeCarId() }
    }
    single<TripRepository> { RoomTripRepository(get()) }
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
        RoomDtcRepository(get(), activeCarId = { cars.activeCarId() })
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
    single<AnalyticsRepository> { RoomAnalyticsRepository(get(), get(), get(), get()) }
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

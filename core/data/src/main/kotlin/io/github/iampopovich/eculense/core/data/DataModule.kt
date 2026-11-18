package io.github.iampopovich.eculense.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.iampopovich.eculense.core.obd.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.binds
import org.koin.dsl.module

val dataModule = module {
    single { DataStoreVehiclePrefs(PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("vehicle") }) } binds
        arrayOf(VehiclePrefs::class, UnitsRepository::class)
    single<CarRepository> { RoomCarRepository(get(), get()) }
    single<TripRepository> { RoomTripRepository(get()) }
    single<SessionRepository> {
        val cars = get<CarRepository>()
        RoomSessionRepository(get(), activeCarId = { cars.activeCarId() })
    }
    single<DtcRepository> {
        val cars = get<CarRepository>()
        RoomDtcRepository(get(), activeCarId = { cars.activeCarId() })
    }
    single<SessionRecorder> { LiveSessionRecorder(get(), get(), get(AppScope)) }
    single<AnalyticsRepository> { RoomAnalyticsRepository(get(), get(), get(), get()) }
    single {
        val scope = get<CoroutineScope>(AppScope)
        val analytics = get<AnalyticsRepository>()
        val cars = get<CarRepository>()
        // Новые записи и коды → находки пересчитываются в фоне для всех машин (их одна-две).
        CarScannerImporter(androidContext(), get(), get(), cars, get(), get()) { report ->
            if (report.imported > 0 || report.tripsImported > 0 || report.dtcEventsAdded > 0) {
                scope.launch { cars.cars().forEach { runCatching { analytics.recompute(it.id) } } }
            }
        }
    }
}

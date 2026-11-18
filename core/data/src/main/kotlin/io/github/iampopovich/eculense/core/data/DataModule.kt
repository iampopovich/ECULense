package io.github.iampopovich.eculense.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.iampopovich.eculense.core.obd.AppScope
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
    single { CarScannerImporter(androidContext(), get(), get(), get(), get(), get()) }
}

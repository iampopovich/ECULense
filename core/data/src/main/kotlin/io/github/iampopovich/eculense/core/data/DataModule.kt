package io.github.iampopovich.eculense.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.iampopovich.eculense.core.database.AnalyticsDao
import io.github.iampopovich.eculense.core.database.TripDao
import io.github.iampopovich.eculense.core.obd.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Реакция на новые данные (запись остановлена, импорт закончен). Реализует необязательный модуль фоновой работы
 * (:core:background — анализ в WorkManager и уведомления). Если его нет, слой данных сам пересчитывает находки.
 */
interface DataEvents {
    fun recordingFinished(sessionId: Long)
    fun importFinished()
}

val dataModule = module {
    single { DataStoreVehiclePrefs(PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("vehicle") }) } binds
        arrayOf(VehiclePrefs::class, UnitsRepository::class, MaintenancePrefs::class)
    single<CarRepository> { RoomCarRepository(get(), get(), get()) }
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
        // Поездка по записи — сразу (быстро, одна запись); анализ и уведомления — в фоновом модуле.
        LiveSessionRecorder(get(), get(), scope) { id ->
            runCatching { trips.backfill() }
            val events = koin.dataEvents()
            if (events.isEmpty()) recomputeAll(koin, scope) else events.forEach { it.recordingFinished(id) }
        }
    }
    single<AnalyticsRepository> { RoomAnalyticsRepository(get(), get(), get(), get()) }
    single {
        val scope = get<CoroutineScope>(AppScope)
        val koin = getKoin()
        val trips = get<RecordingTrips>()
        // Новые записи и коды → поездки по записям без поездки Car Scanner, затем находки (в фоне, для всех машин).
        CarScannerImporter(androidContext(), get(), get(), get(), get(), get()) { report ->
            if (report.imported > 0 || report.tripsImported > 0 || report.dtcEventsAdded > 0) {
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

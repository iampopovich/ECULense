package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.obd.AppScope
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val dataModule = module {
    single<SessionRepository> { RoomSessionRepository(get()) }
    single<DtcRepository> { RoomDtcRepository(get()) }
    single<SessionRecorder> { LiveSessionRecorder(get(), get(), get(AppScope)) }
    single { CarScannerImporter(androidContext(), get(), get()) }
}

package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/** Coroutine scope that lives as long as the app process. */
val AppScope = named("app-scope")

fun obdModule(includeEmulator: Boolean) = module {
    single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<ObdTrafficLog> { RingTrafficLog() }
    single<ConnectionController> {
        ObdConnectionManager(getAll(), get(AppScope), trafficLog = get(), prefs = getOrNull<AdapterPrefs>() ?: InMemoryAdapterPrefs())
    }
    single(named(WifiTransportFactory.KIND)) { WifiTransportFactory(includeEmulator) } bind TransportFactory::class
    single { ReplayLogHolder() }
    single(named(ReplayTransportFactory.KIND)) {
        val holder = get<ReplayLogHolder>()
        ReplayTransportFactory("Replay a traffic log", listedWithoutLog = true) { holder.text }
    } bind TransportFactory::class
}

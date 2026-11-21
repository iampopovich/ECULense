package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.AppLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/** Coroutine scope that lives as long as the app process. */
val AppScope = named("app-scope")

/**
 * The app-wide scope: a failing child is logged instead of killing the process (a crash mid-trip loses the
 * recording); the supervisor keeps the other children running.
 */
fun appScope(logger: AppLogger): CoroutineScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
        logger.error("EcuAppScope", "uncaught exception in the app scope: ${e::class.java.simpleName}", e)
    },
)

fun obdModule(includeEmulator: Boolean) = module {
    single<CoroutineScope>(AppScope) { appScope(getOrNull<AppLogger>() ?: AppLogger.NONE) }
    single<ObdTrafficLog> { RingTrafficLog() }
    single<ConnectionController> {
        ObdConnectionManager(getAll(), get(AppScope), trafficLog = get(), prefs = getOrNull<AdapterPrefs>() ?: InMemoryAdapterPrefs())
    }
    single(named(WifiTransportFactory.KIND)) {
        val scope = this
        WifiTransportFactory(includeEmulator) { scope.getOrNull<AdapterPrefs>()?.suggestedWifi?.first() }
    } bind TransportFactory::class
    single { ReplayLogHolder() }
    single(named(ReplayTransportFactory.KIND)) {
        val holder = get<ReplayLogHolder>()
        ReplayTransportFactory("Replay a traffic log", listedWithoutLog = true) { holder.text }
    } bind TransportFactory::class
}

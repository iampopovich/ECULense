package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/** Область корутин, живущая столько же, сколько процесс приложения. */
val AppScope = named("app-scope")

fun obdModule(includeEmulator: Boolean) = module {
    single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<ConnectionController> { ObdConnectionManager(getAll(), get(AppScope)) }
    single(named(WifiTransportFactory.KIND)) { WifiTransportFactory(includeEmulator) } bind TransportFactory::class
}

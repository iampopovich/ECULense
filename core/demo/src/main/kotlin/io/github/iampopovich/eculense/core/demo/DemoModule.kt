package io.github.iampopovich.eculense.core.demo

import io.github.iampopovich.eculense.core.obd.TransportFactory
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/** Demo mode: a virtual ELM327 shown in the adapter list as a regular transport. */
val demoModule = module {
    single(named(DemoTransportFactory.KIND)) { DemoTransportFactory() } bind TransportFactory::class
    single(named(FlakyDemoTransportFactory.KIND)) { FlakyDemoTransportFactory() } bind TransportFactory::class
    single(named(EngineStopDemoTransportFactory.KIND)) { EngineStopDemoTransportFactory() } bind TransportFactory::class
}

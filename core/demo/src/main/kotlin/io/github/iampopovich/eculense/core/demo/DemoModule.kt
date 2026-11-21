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
    // A: the demo car over the BLE code path (GattUartTransport + simulated GATT notifications).
    single(named(BleDemoTransportFactory.KIND)) { BleDemoTransportFactory() } bind TransportFactory::class
    // R-D: several modules on CAN (engine, transmission, ABS, airbags never asked) and the owner's K-Line KWP shape.
    single(named(MultiEcuDemoTransportFactory.KIND)) { MultiEcuDemoTransportFactory() } bind TransportFactory::class
    single(named(KLineDemoTransportFactory.KIND)) { KLineDemoTransportFactory() } bind TransportFactory::class
    // R-E: a Kia Niro EV answering with OBDb test-case frames (extended sensors, EV card).
    single(named(NiroEvDemoTransportFactory.KIND)) { NiroEvDemoTransportFactory() } bind TransportFactory::class
}

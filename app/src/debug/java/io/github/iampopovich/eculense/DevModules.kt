package io.github.iampopovich.eculense

import io.github.iampopovich.eculense.core.demo.NiroEvDemoTransportFactory
import io.github.iampopovich.eculense.core.demo.ObdbFixtures
import io.github.iampopovich.eculense.core.demo.demoModule
import io.github.iampopovich.eculense.core.obdb.SignalsetParser
import io.github.iampopovich.eculense.core.obdb.SimulatedSignalset
import io.github.iampopovich.eculense.core.obdb.StoredSignalset
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.EndpointLabel
import io.github.iampopovich.eculense.core.obd.ReplayLogHolder
import io.github.iampopovich.eculense.core.obd.ReplayTransportFactory
import io.github.iampopovich.eculense.core.obd.TransportFactory
import io.github.iampopovich.eculense.core.obd.WifiTransportFactory
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * R-I: developer endpoints exist only in debug builds (release has an empty list in app/src/release): the demo
 * adapters (virtual ELM327, flaky clone, engine stops) and the traffic log replay. The Connect screen groups them in
 * the collapsed «For development» section; nothing on these links is stored for the active car (LiveWriteGate).
 */
val devModules: List<Module> = listOf(
    demoModule,
    module {
        single { ReplayLogHolder() }
        single(named(ReplayTransportFactory.KIND)) {
            val holder = get<ReplayLogHolder>()
            ReplayTransportFactory("Replay a traffic log", listedWithoutLog = true) { holder.text }
        } bind TransportFactory::class
        // R-E: the «Demo: Niro EV (OBDb)» adapter brings its signalset (bundled OBDb file, CC BY-SA 4.0): no demo car.
        single<SimulatedSignalset> { NiroEvDemoSignalset }
    },
)

/** The Kia-Niro-EV signalset bundled with :core:demo for the Niro EV demo adapter (parsed once, on first use). */
private object NiroEvDemoSignalset : SimulatedSignalset {
    override val endpointKind = NiroEvDemoTransportFactory.KIND
    override val year = NiroEvDemoTransportFactory.YEAR
    private val parsed by lazy { StoredSignalset("OBDb/Kia-Niro-EV", "demo", SignalsetParser.parse(ObdbFixtures.signalset(ObdbFixtures.NIRO_EV))) }
    override fun signalset() = parsed
}

/** The ELM327 emulator on the developer's PC, as seen from the Android emulator (10.0.2.2). */
val devWifiEndpoints: List<AdapterEndpoint> = listOf(
    AdapterEndpoint(WifiTransportFactory.KIND, "10.0.2.2:35000", "ELM327 emulator on PC (10.0.2.2)", EndpointLabel.PC_EMULATOR),
)

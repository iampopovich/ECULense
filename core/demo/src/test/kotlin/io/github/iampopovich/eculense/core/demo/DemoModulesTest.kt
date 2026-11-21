package io.github.iampopovich.eculense.core.demo

import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.ModuleDtcRead
import io.github.iampopovich.eculense.core.obd.ModuleDtcService
import io.github.iampopovich.eculense.core.obd.ModuleKind
import io.github.iampopovich.eculense.core.obd.ModuleScanner
import io.github.iampopovich.eculense.core.obd.ObdConnectionManager
import io.github.iampopovich.eculense.core.obd.ObdTransport
import io.github.iampopovich.eculense.core.obd.StandardPids
import io.github.iampopovich.eculense.core.obd.TransportFactory
import io.github.iampopovich.eculense.core.obd.isSimulated
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-D: the multi-ECU and K-Line demos through the real :core:obd stack. */
class DemoModulesTest {
    private fun TestScope.connect(bus: DemoBus, endpoint: AdapterEndpoint): Pair<ObdConnectionManager, DemoTransport> {
        val transport = DemoTransport(clock = { testScheduler.currentTime }, latencyMs = { 1 }, bus = bus)
        val factory = object : TransportFactory {
            override val kind = endpoint.kind
            override suspend fun discover() = listOf(endpoint)
            override fun create(endpoint: AdapterEndpoint): ObdTransport = transport
        }
        val manager = ObdConnectionManager(listOf(factory), backgroundScope, clock = { testScheduler.currentTime })
        manager.connect(endpoint)
        return manager to transport
    }

    @Test
    fun `CAN demo lists engine, transmission and ABS, never queries the airbag module`() = runTest {
        val (manager, transport) = connect(DemoBus.CAN_MULTI, MultiEcuDemoTransportFactory.ENDPOINT)
        val connected = manager.state.first { it is ConnectionState.Connected } as ConnectionState.Connected
        assertTrue(connected.isSimulated)
        val result = manager.withExclusiveSession { ModuleScanner().scan(it) }!!
        assertEquals(listOf(ModuleKind.ENGINE, ModuleKind.TRANSMISSION, ModuleKind.ABS_ESC), result.modules.map { it.module.kind })
        val engine = result.modules[0].read as ModuleDtcRead.Codes
        assertEquals(ModuleDtcService.MODE_03, engine.service)
        assertEquals(listOf("P0133", "P0300", "P0171"), engine.dtcs.map { it.code })
        val tcm = result.modules[1].read as ModuleDtcRead.Codes
        assertEquals(ModuleDtcService.UDS_19_02, tcm.service)
        assertEquals(listOf("P0715"), tcm.dtcs.map { it.code })
        val abs = result.modules[2].read as ModuleDtcRead.Codes
        assertEquals(listOf("C0035", "C0040"), abs.dtcs.map { it.code })
        // The SRS module exists in the simulation but nothing was ever written to it.
        assertEquals(0, transport.srsRequests)
        assertTrue(transport.requests.none { it.first == "7D2" || it.first == "7D4" })
        assertTrue(result.skipped.contains("7D2/7DA"))
        // Headers are back off: the normal reads decode again.
        val rpm = manager.withSession { it.read(StandardPids.byCode(0x0C)!!) }
        assertTrue(rpm!!.value > 0)
        assertEquals(listOf("P0133", "P0300"), manager.withSession { it.readDtcs(DtcKind.STORED) }!!.map { it.code })
        manager.disconnect()
    }

    @Test
    fun `K-Line KWP demo answers like the owner's car and the scan stays functional`() = runTest {
        val (manager, transport) = connect(DemoBus.KLINE_KWP, KLineDemoTransportFactory.ENDPOINT)
        val connected = manager.state.first { it is ConnectionState.Connected } as ConnectionState.Connected
        assertEquals('5', connected.info.protocolNumber)
        assertFalse(connected.info.isCan)
        assertTrue(connected.isSimulated)
        assertEquals(listOf("P0133", "P0300"), manager.withSession { it.readDtcs(DtcKind.STORED) }!!.map { it.code })

        val result = manager.withExclusiveSession { ModuleScanner().scan(it) }!!
        assertEquals(listOf("10", "18"), result.modules.map { it.module.address.rx })
        val engine = result.modules[0].read as ModuleDtcRead.Codes
        assertEquals(ModuleDtcService.KWP_18_00, engine.service)
        assertEquals(listOf("P0133", "P0300"), engine.dtcs.map { it.code })
        val tcm = result.modules[1].read as ModuleDtcRead.Codes
        assertEquals(ModuleDtcService.MODE_03, tcm.service)
        assertTrue(tcm.dtcs.isEmpty())
        // Every request went out under the functional KWP header (C1 33 F1, the ELM adds the length: C2 33 F1).
        assertTrue(transport.requests.all { it.first == "C133F1" })
        val rpm = manager.withSession { it.read(StandardPids.byCode(0x0C)!!) }
        assertTrue(rpm!!.value > 0)
        manager.disconnect()
    }
}

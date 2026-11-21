package io.github.iampopovich.eculense.core.demo

import io.github.iampopovich.eculense.core.obd.GattUartTransport
import io.github.iampopovich.eculense.core.obd.ObdConnectionManager
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.StandardPids
import io.github.iampopovich.eculense.core.obd.UartTier
import io.github.iampopovich.eculense.core.obd.isSimulated
import io.github.iampopovich.eculense.core.model.SensorReading
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DemoBleTest {
    @Test
    fun `random split keeps every byte and never exceeds 20`() {
        val link = DemoGattLink(DemoTransport(), Random(7))
        val bytes = ("014\r0:490201314434\r1:47503030523535\r2:42313233343536\r\r>").encodeToByteArray()
        repeat(50) {
            val parts = link.split(bytes)
            assertTrue(parts.all { it.size in 1..20 })
            assertEquals(bytes.decodeToString(), parts.joinToString("") { it.decodeToString() })
        }
    }

    @Test
    fun `demo BLE endpoint is simulated`() {
        assertTrue(BleDemoTransportFactory.ENDPOINT.isSimulated)
    }

    @Test
    fun `demo car is polled over the GATT transport`() = runTest {
        val clock = { testScheduler.currentTime }
        val factory = BleDemoTransportFactory(clock = clock, latencyMs = { 30L }, scope = backgroundScope)
        val manager = ObdConnectionManager(listOf(factory), backgroundScope, clock = clock)
        manager.connect(BleDemoTransportFactory.ENDPOINT)
        val connected = manager.state.first { it is ConnectionState.Connected || it is ConnectionState.Failed }
        assertTrue("$connected", connected is ConnectionState.Connected)
        manager.setPolledPids(listOf(StandardPids.byCode(0x0C)!!, StandardPids.byCode(0x0D)!!))
        val readings = mutableListOf<SensorReading>()
        backgroundScope.launch { manager.readings.collect { readings += it } }
        delay(3_000)
        assertTrue("${readings.size}", readings.size >= 10)
        manager.disconnect()
    }

    @Test
    fun `the demo table selects the FFF0 tier`() = runTest {
        val transport = GattUartTransport(DemoGattLink(DemoTransport(), scope = backgroundScope))
        transport.open()
        assertEquals(UartTier.FFF0, transport.uartPair!!.tier)
        transport.close()
    }
}

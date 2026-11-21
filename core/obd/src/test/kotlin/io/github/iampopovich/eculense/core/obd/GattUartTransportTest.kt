package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.SensorReading
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** A: ELM327 over a GATT serial pair (notification buffer, write chunking, link loss). */
class GattUartTransportTest {
    private fun transport(link: GattLink, limit: BleWriteLimit = BleWriteLimit.CONSERVATIVE, cached: GattUartPair? = null, chosen: (GattUartPair) -> Unit = {}) =
        GattUartTransport(link, cached, chosen, limit)

    @Test
    fun `a reply split into 20-byte notifications reads as one ELM reply`() = runTest {
        val vin = "014\r0:490201314434\r1:47503030523535\r2:42313233343536"
        val link = FakeGattLink(replies = mapOf("0902" to vin))
        val t = transport(link)
        t.open()
        assertEquals("$vin\r\r", ElmLink(t).send("0902"))
        assertTrue(link.writes.isNotEmpty())
        assertEquals(GattTables.uuid16(0xFFF0) to GattTables.uuid16(0xFFF1), link.enabled)
        t.close()
    }

    @Test
    fun `the prompt arriving in its own notification after a split CR still ends the reply`() = runTest {
        val link = FakeGattLink(replies = mapOf("ATRV" to "12.6V"))
        // "12.6V\r" | "\r" | ">": the prompt is a separate notification.
        link.split = { bytes -> val s = bytes.decodeToString(); listOf(s.dropLast(2), s.substring(s.length - 2, s.length - 1), ">").map { it.encodeToByteArray() } }
        val t = transport(link)
        t.open()
        assertEquals("12.6V\r\r", ElmLink(t).send("ATRV"))
    }

    @Test
    fun `a notification that comes before the write returns is not lost`() = runTest {
        // FakeGattLink answers inside write(); a second unsolicited fragment comes in the same call.
        val link = FakeGattLink(replies = mapOf("ATI" to "ELM327 v1.5"))
        val t = transport(link)
        t.open()
        t.write("ATI\r".encodeToByteArray())
        val got = StringBuilder()
        while (!got.endsWith(">")) got.append(t.read().decodeToString())
        assertEquals("ELM327 v1.5\r\r>", got.toString())
        assertNull(t.readAvailable())
    }

    @Test
    fun `writes are chunked to 20 bytes for unknown adapters even with MTU 517`() = runTest {
        val long = "AT" + "X".repeat(40) // 42 chars + CR
        for (mtu in listOf(23, 517)) {
            val link = FakeGattLink(mtu = mtu)
            val t = transport(link)
            t.open()
            t.write("$long\r".encodeToByteArray())
            assertEquals("mtu $mtu", listOf(20, 20, 3), link.writes.map { it.bytes.size })
        }
    }

    @Test
    fun `the negotiated limit uses MTU - 3 when the adapter is known to handle it`() = runTest {
        val payload = ("A".repeat(600) + "\r").encodeToByteArray()
        val small = FakeGattLink(mtu = 23)
        transport(small, BleWriteLimit.NEGOTIATED).apply { open(); write(payload) }
        assertEquals(31, small.writes.size)
        assertTrue(small.writes.all { it.bytes.size <= 20 })
        val big = FakeGattLink(mtu = 517)
        transport(big, BleWriteLimit.NEGOTIATED).apply { open(); write(payload) }
        assertEquals(listOf(514, 87), big.writes.map { it.bytes.size })
        assertArrayEquals(payload, big.writes.fold(ByteArray(0)) { acc, w -> acc + w.bytes })
    }

    @Test
    fun `write without response is used when the characteristic supports it`() = runTest {
        val cx = FakeGattLink(GattTables.OBDLINK_CX)
        transport(cx).apply { open(); write("ATZ\r".encodeToByteArray()) }
        assertTrue(cx.writes.all { !it.withResponse })
        val viecar = FakeGattLink(GattTables.VIECAR)
        transport(viecar).apply { open(); write("ATZ\r".encodeToByteArray()) }
        assertTrue(viecar.writes.all { it.withResponse })
    }

    @Test
    fun `a dropped link fails the pending read with an IOException`() = runTest {
        val link = FakeGattLink(replies = mapOf("0100" to "NO DATA"))
        val t = transport(link)
        t.open()
        val reader = backgroundScope.launch {
            try {
                t.read()
                fail("read must fail")
            } catch (e: IOException) {
                // expected
            }
        }
        delay(10)
        link.drop()
        withTimeout(1_000) { reader.join() }
        try {
            t.write("0100\r".encodeToByteArray())
            fail("write must fail")
        } catch (e: IOException) {
            assertEquals(ObdError.BleDisconnected, ObdError.of(e))
        }
    }

    @Test
    fun `a device without a serial pair fails with a typed error and is closed`() = runTest {
        val link = FakeGattLink(GattTables.NOT_AN_ADAPTER)
        try {
            transport(link).open()
            fail("open must fail")
        } catch (e: IOException) {
            assertEquals(ObdError.BleNoUartService, ObdError.of(e))
        }
        assertTrue(link.closed)
    }

    @Test
    fun `the chosen pair is reported and a valid cached pair is reused`() = runTest {
        var chosen: GattUartPair? = null
        transport(FakeGattLink(GattTables.VGATE), chosen = { chosen = it }).open()
        assertEquals(UartTier.VGATE_18F0, chosen!!.tier)

        // A cached pair that the device still has wins over the tier order.
        val vendor = GattUartSelector.select(GattTables.VGATE.filter { it.uuid != GattTables.uuid16(0x18F0) })!!
        val link = FakeGattLink(GattTables.VGATE)
        var reported: GattUartPair? = null
        transport(link, cached = vendor, chosen = { reported = it }).open()
        assertEquals(vendor.service to vendor.notify, link.enabled)
        assertEquals(vendor, reported)

        // A stale cached pair (other device behind the same address) is ignored.
        val stale = GattUartSelector.select(GattTables.NORDIC_UART)!!
        val other = FakeGattLink(GattTables.VIECAR)
        transport(other, cached = stale).open()
        assertEquals(GattTables.uuid16(0xFFF0) to GattTables.uuid16(0xFFF1), other.enabled)
    }

    @Test
    fun `full ELM session runs over BLE notifications`() = runTest {
        val link = FakeGattLink()
        val session = Elm327Session(ElmLink(transport(link).also { it.open() }))
        val info = session.initialize()
        assertEquals("ELM327 v1.5", info.version)
        assertTrue(link.sentCommands.first().startsWith("ATZ") || link.sentCommands.contains("ATZ"))
    }

    @Test
    fun `link drop mid-session reconnects through a new GATT link`() = runTest {
        val links = mutableListOf<FakeGattLink>()
        val car = FakeCar.replies + mapOf("010C1" to "410C1AF8", "ATAT1" to "OK", "ATSPA6" to "OK")
        val factory = object : TransportFactory {
            override val kind = "ble"
            override suspend fun discover() = emptyList<AdapterEndpoint>()
            override fun create(endpoint: AdapterEndpoint): ObdTransport =
                GattUartTransport(FakeGattLink(replies = car, latencyMs = 20).also { links += it })
        }
        val manager = ObdConnectionManager(listOf(factory), backgroundScope, clock = { testScheduler.currentTime })
        val endpoint = AdapterEndpoint("ble", "00:11:22:33:44:55", "Test BLE")
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connected }
        manager.setPolledPids(listOf(StandardPids.byCode(0x0C)!!))
        val readings = mutableListOf<SensorReading>()
        backgroundScope.launch { manager.readings.collect { readings += it } }
        delay(1_000)
        assertTrue(readings.isNotEmpty())

        links[0].drop()
        manager.state.first { it is ConnectionState.Reconnecting }
        manager.state.first { it is ConnectionState.Connected }
        val before = readings.size
        delay(1_000)
        assertTrue("${readings.size} > $before", readings.size > before)
        assertEquals(2, links.size)
        assertTrue(links[0].closed)
    }
}

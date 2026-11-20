package io.github.iampopovich.eculense.core.demo

import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.EndpointLabel
import io.github.iampopovich.eculense.core.obd.ObdConnectionManager
import io.github.iampopovich.eculense.core.obd.StandardPids
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
/** The demo adapter is tested through the real :core:obd stack (ObdConnectionManager → Elm327Session → ElmLink). */
class DemoTransportTest {

    private suspend fun ObdConnectionManager.connectDemo(): ConnectionState.Connected {
        connect(DemoTransportFactory.ENDPOINT)
        val state = state.first { it is ConnectionState.Connected || it is ConnectionState.Failed }
        assertTrue("ожидали Connected, получили $state", state is ConnectionState.Connected)
        return state as ConnectionState.Connected
    }

    @Test
    fun `factory offers a single demo endpoint`() = runTest {
        val endpoints = DemoTransportFactory().discover()
        assertEquals(1, endpoints.size)
        assertEquals(DemoTransportFactory.KIND, endpoints.single().kind)
        assertEquals(EndpointLabel.DEMO, endpoints.single().labelKey)
    }

    @Test
    fun `initialize reports ELM327 on CAN protocol 6`() = runTest {
        val manager = ObdConnectionManager(listOf(DemoTransportFactory(clock = { testScheduler.currentTime })), backgroundScope)
        val state = manager.connectDemo()
        assertEquals('6', state.info.protocolNumber)
        assertTrue(state.info.isCan)
        assertTrue(state.info.version, state.info.version.startsWith("ELM327"))
        val voltage = state.info.batteryVoltage!!
        assertTrue("$voltage", voltage in 14.0..14.5)
    }

    @Test
    fun `supported pids cover every standard pid`() = runTest {
        val manager = ObdConnectionManager(listOf(DemoTransportFactory(clock = { testScheduler.currentTime })), backgroundScope)
        val state = manager.connectDemo()
        StandardPids.all.forEach { pid -> assertTrue("PID ${pid.sensorId}", pid.code in state.supportedPids) }
        assertTrue(0x0C in state.supportedPids)
    }

    @Test
    fun `every supported pid is answered and decodes into a plausible value`() = runTest {
        val manager = ObdConnectionManager(listOf(DemoTransportFactory(clock = { testScheduler.currentTime })), backgroundScope)
        manager.connectDemo()
        manager.withSession { session ->
            StandardPids.all.forEach { pid ->
                val reading = session.read(pid)
                assertNotNull("PID ${pid.sensorId} не ответил", reading)
                assertEquals(pid.unit, reading!!.unit)
            }
            val rpm = session.read(StandardPids.byCode(0x0C)!!)!!.value
            assertTrue("rpm=$rpm", rpm in 600.0..7000.0)
            val voltage = session.read(StandardPids.byCode(0x42)!!)!!.value
            assertTrue("voltage=$voltage", voltage in 14.0..14.5)
        }
    }

    @Test
    fun `encoded values round-trip through StandardPids decoding`() {
        val engine = DemoEngine()
        val transport = DemoTransport(clock = { 0L }, latencyMs = { 0L }, engine = engine)
        runBlocking {
            transport.open()
            for (t in listOf(0L, 15_000L, 35_000L, 47_000L, 300_000L)) {
                val expected = engine.values(t)
                StandardPids.all.forEach { pid ->
                    val reply = DemoTransport.encodePid(pid.code, expected)!!
                    val decoded = pid.decodeFrom(reply.hexBytes())!!
                    val tolerance = when (pid.code) {
                        0x04, 0x11, 0x2F -> 0.4
                        0x06, 0x07 -> 0.8
                        else -> 0.6
                    }
                    assertEquals("PID ${pid.sensorId} @ $t", expected.getValue(pid.code), decoded, tolerance)
                }
            }
        }
    }

    @Test
    fun `unsupported pid answers NO DATA and unknown command answers question mark`() = runTest {
        val transport = DemoTransport(clock = { 0L }, latencyMs = { 0L })
        transport.open()
        transport.ask("ATE0")
        transport.ask("0100")
        assertEquals("NO DATA", transport.ask("0150"))
        assertEquals("?", transport.ask("HELLO"))
        assertEquals("?", transport.ask("ATXYZ"))
    }

    @Test
    fun `echo and spaces follow ATE and ATS`() = runTest {
        val transport = DemoTransport(clock = { 0L }, latencyMs = { 0L })
        transport.open()
        assertEquals("ATI\rELM327 v1.5", transport.ask("ATI"))
        transport.ask("ATE0")
        assertTrue(transport.ask("010D").startsWith("SEARCHING...\r41 0D "))
        transport.ask("ATS0")
        assertTrue(transport.ask("010D").startsWith("410D"))
        transport.ask("ATH1")
        assertTrue(transport.ask("010D").startsWith("7E803410D"))
        assertEquals("A6", transport.ask("ATDPN"))
    }

    @Test
    fun `stored, pending and permanent DTCs then clear`() = runTest {
        val manager = ObdConnectionManager(listOf(DemoTransportFactory(clock = { testScheduler.currentTime })), backgroundScope)
        manager.connectDemo()
        manager.withSession { session ->
            assertEquals(listOf("P0133", "P0300"), session.readDtcs(DtcKind.STORED).map { it.code })
            assertEquals(listOf("P0171"), session.readDtcs(DtcKind.PENDING).map { it.code })
            assertTrue(session.readDtcs(DtcKind.PERMANENT).isEmpty())
            assertTrue(session.clearDtcs())
            assertTrue(session.readDtcs(DtcKind.STORED).isEmpty())
            assertTrue(session.readDtcs(DtcKind.PENDING).isEmpty())
        }
    }

    @Test
    fun `VIN comes as CAN multi-frame and decodes to 17 chars`() = runTest {
        val manager = ObdConnectionManager(listOf(DemoTransportFactory(clock = { testScheduler.currentTime })), backgroundScope)
        manager.connectDemo()
        val vin = manager.withSession { it.readVin() }
        assertEquals(DemoEngine.VIN, vin)
        assertEquals(17, vin!!.length)
    }

    @Test
    fun `live readings flow through the poller`() = runTest {
        val manager = ObdConnectionManager(listOf(DemoTransportFactory(clock = { testScheduler.currentTime })), backgroundScope)
        manager.connectDemo()
        manager.setPolledPids(listOf(StandardPids.byCode(0x0C)!!, StandardPids.byCode(0x05)!!))
        val readings = manager.readings.take(6).toList()
        assertEquals(setOf("01.0C", "01.05"), readings.map { it.sensorId }.toSet())
    }

    @Test
    fun `signals evolve with the clock`() = runTest {
        var now = 0L
        val manager = ObdConnectionManager(listOf(DemoTransportFactory(clock = { now })), backgroundScope)
        manager.connectDemo()
        val coolant = StandardPids.byCode(0x05)!!
        val fuel = StandardPids.byCode(0x2F)!!
        val speed = StandardPids.byCode(0x0D)!!
        val rpm = StandardPids.byCode(0x0C)!!
        manager.withSession { s ->
            val coldCoolant = s.read(coolant)!!.value
            val idleRpm = s.read(rpm)!!.value
            val startFuel = s.read(fuel)!!.value
            assertTrue("coolant=$coldCoolant", coldCoolant in 15.0..30.0)
            assertEquals(0.0, s.read(speed)!!.value, 0.0)
            assertTrue("cold idle rpm=$idleRpm", idleRpm in 700.0..1200.0)

            now = 30_000 // mid-cycle: cruising speed
            val cruise = s.read(speed)!!.value
            assertTrue("cruise speed=$cruise", cruise > 40.0)
            assertTrue(s.read(rpm)!!.value > 1500.0)

            now = 15 * 60_000L + 5_000 // warmed-up engine at idle
            val warm = s.read(coolant)!!.value
            assertTrue("warm coolant=$warm", warm in 85.0..95.0)
            val warmIdle = s.read(rpm)!!.value
            assertTrue("warm idle rpm=$warmIdle", warmIdle in 700.0..900.0)
            assertTrue(s.read(fuel)!!.value < startFuel)
        }
    }

    @Test
    fun `engine model keeps signals coherent across a drive cycle`() {
        val engine = DemoEngine()
        var previousSpeed = 0.0
        for (t in 0L..600_000L step 1_000L) {
            val v = engine.values(t)
            val speed = v.getValue(0x0D)
            val rpm = v.getValue(0x0C)
            assertTrue("speed=$speed @ $t", speed in 0.0..140.0)
            assertTrue("rpm=$rpm @ $t", rpm in 600.0..5000.0)
            assertTrue("throttle", v.getValue(0x11) in 0.0..100.0)
            assertTrue("load", v.getValue(0x04) in 0.0..100.0)
            assertTrue("maf", v.getValue(0x10) in 0.5..80.0)
            assertTrue("voltage", v.getValue(0x42) in 14.1..14.4)
            assertTrue("speed jump @ $t", kotlin.math.abs(speed - previousSpeed) < 12.0)
            previousSpeed = speed
        }
    }
}

internal fun String.hexBytes(): ByteArray =
    replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Send a command and return the response without the prompt and trailing \r. */
internal suspend fun DemoTransport.ask(command: String): String {
    write("$command\r".encodeToByteArray())
    val sb = StringBuilder()
    while ('>' !in sb) sb.append(read().decodeToString())
    return sb.substring(0, sb.indexOf(">")).trimEnd('\r', '\n')
}

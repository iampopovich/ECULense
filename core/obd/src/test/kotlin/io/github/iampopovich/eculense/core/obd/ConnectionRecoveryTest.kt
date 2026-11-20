package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.SensorReading
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch 2 at connection level: reconnect with backoff (A3), engine off (A4), protocol cache (A5), A9, A10. */
class ConnectionRecoveryTest {
    private val endpoint = AdapterEndpoint("fake", "00:11", "Fake ELM")
    private val rpm = StandardPids.byCode(0x0C)!!
    private val speed = StandardPids.byCode(0x0D)!!
    private val car = FakeCar.replies + mapOf("010C1" to "410C1AF8", "010D1" to "410D20", "ATAT1" to "OK", "ATSPA6" to "OK")

    private val transports = mutableListOf<SwitchableTransport>()

    private fun factory(make: (Int) -> SwitchableTransport) = object : TransportFactory {
        override val kind = "fake"
        override suspend fun discover() = listOf(endpoint)
        override fun create(endpoint: AdapterEndpoint): ObdTransport = make(transports.size).also { transports += it }
    }

    private fun TestScope.manager(prefs: AdapterPrefs = InMemoryAdapterPrefs(), make: (Int) -> SwitchableTransport = { transport() }) =
        ObdConnectionManager(listOf(factory(make)), backgroundScope, prefs = prefs, clock = { testScheduler.currentTime })

    private fun transport(replies: Map<String, String> = car) = SwitchableTransport(replies).apply { latencyMs = 20 }

    private fun TestScope.collect(manager: ObdConnectionManager): MutableList<SensorReading> {
        val collected = mutableListOf<SensorReading>()
        backgroundScope.launch { manager.readings.collect { collected += it } }
        return collected
    }

    private fun TestScope.states(manager: ObdConnectionManager): MutableList<ConnectionState> {
        val states = mutableListOf<ConnectionState>()
        backgroundScope.launch { manager.state.collect { states += it } }
        return states
    }

    @Test
    fun `lost link reconnects with backoff and polling continues`() = runTest {
        val manager = manager()
        val states = states(manager)
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connected }
        manager.setPolledPids(listOf(rpm))
        val readings = collect(manager)
        delay(1_000)
        assertTrue(readings.isNotEmpty())

        transports[0].breakLink()
        val reconnecting = manager.state.first { it is ConnectionState.Reconnecting } as ConnectionState.Reconnecting
        assertEquals(1, reconnecting.attempt)
        assertEquals(1_000L, reconnecting.nextInMs)
        manager.state.first { it is ConnectionState.Connected }
        val before = readings.size
        delay(1_000)
        assertTrue("${readings.size} > $before", readings.size > before)
        assertEquals(2, transports.size)
        assertTrue(states.none { it is ConnectionState.Failed })
    }

    @Test
    fun `reconnect gives up after about two minutes`() = runTest {
        val manager = manager { index -> transport().also { if (index > 0) it.breakLink() } }
        val states = states(manager)
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connected }
        manager.setPolledPids(listOf(rpm))
        collect(manager)
        delay(500)
        transports[0].breakLink()
        val failed = manager.state.first { it is ConnectionState.Failed } as ConnectionState.Failed
        assertTrue("${failed.error}", failed.error is ObdError.LinkLost)
        val waits = states.filterIsInstance<ConnectionState.Reconnecting>().map { it.nextInMs }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), waits)
    }

    @Test
    fun `disconnect stops reconnecting`() = runTest {
        val manager = manager { index -> transport().also { if (index > 0) it.breakLink() } }
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connected }
        manager.setPolledPids(listOf(rpm))
        collect(manager)
        delay(500)
        transports[0].breakLink()
        manager.state.first { it is ConnectionState.Reconnecting }
        manager.disconnect()
        delay(200_000)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertTrue("${transports.size}", transports.size <= 2)
    }

    @Test
    fun `ECU silent while the adapter answers is engine off, then polling resumes`() = runTest {
        val manager = manager()
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connected }
        manager.setPolledPids(listOf(rpm))
        val readings = collect(manager)
        delay(500)
        val t = transports[0]
        listOf("010C1", "010C", "0100").forEach { t.replies[it] = "NO DATA" }
        t.replies["ATRV"] = "12.2V"
        val off = manager.state.first { it is ConnectionState.EngineOff } as ConnectionState.EngineOff
        assertEquals(12.2, off.voltage!!, 0.0)
        // gentle: no PID requests while the engine is off
        val sentAtOff = t.sent.size
        delay(12_000)
        assertTrue(t.sent.drop(sentAtOff).none { it.startsWith("010C") })
        assertTrue(t.sent.drop(sentAtOff).count { it == "ATRV" } >= 2)

        t.replies.putAll(car)
        manager.state.first { it is ConnectionState.Connected }
        val before = readings.size
        delay(1_000)
        assertTrue(readings.size > before)
        assertEquals(1, transports.size) // no reconnect storm
    }

    @Test
    fun `PID without data three cycles in a row is disabled until reconnect`() = runTest {
        val manager = manager { transport(car + ("010D1" to "NO DATA")) }
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connected }
        manager.setPolledPids(listOf(rpm, speed))
        collect(manager)
        manager.disabledPids.first { 0x0D in it }
        val t = transports[0]
        val count = t.sent.size
        delay(1_000)
        assertTrue(t.sent.drop(count).none { it.startsWith("010D") })
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connecting }
        manager.state.first { it is ConnectionState.Connected }
        assertEquals(emptySet<Int>(), manager.disabledPids.value)
    }

    @Test
    fun `poll stats report the cycle time`() = runTest {
        val manager = manager()
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connected }
        manager.setPolledPids(listOf(rpm))
        collect(manager)
        val stats = manager.pollStats.first { (it?.cycles ?: 0) >= 3 }!!
        assertEquals(20L, stats.cycleMsP50)
        assertEquals(20L, stats.pidMsP50[0x0C])
    }

    @Test
    fun `detected protocol is cached per adapter and used next time`() = runTest {
        val prefs = InMemoryAdapterPrefs()
        val manager = manager(prefs)
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connected }
        assertEquals('6', prefs.profiles["fake|00:11"]?.protocol)
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connecting }
        manager.state.first { it is ConnectionState.Connected }
        assertTrue("ATSPA6" in transports[1].sent)
    }

    @Test
    fun `K-Line gets a keepalive while not polled, CAN does not`() = runTest {
        val kLine = manager { transport(car + ("ATDPN" to "A3")) }
        kLine.connect(endpoint)
        kLine.state.first { it is ConnectionState.Connected }
        val t = transports[0]
        val afterInit = t.sent.size
        delay(10_000)
        assertTrue(t.sent.drop(afterInit).count { it == "0100" } >= 1)
        kLine.disconnect()

        transports.clear()
        val can = manager()
        can.connect(endpoint)
        can.state.first { it is ConnectionState.Connected }
        val canTransport = transports[0]
        val canAfterInit = canTransport.sent.size
        delay(10_000)
        assertEquals(canAfterInit, canTransport.sent.size)
    }
}

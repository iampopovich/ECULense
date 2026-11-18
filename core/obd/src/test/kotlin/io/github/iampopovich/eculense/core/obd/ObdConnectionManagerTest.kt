package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ObdConnectionManagerTest {
    private val endpoint = AdapterEndpoint("fake", "00:11", "Fake ELM")

    private fun factory(newTransport: () -> ObdTransport) = object : TransportFactory {
        override val kind = "fake"
        override suspend fun discover() = listOf(endpoint)
        override fun create(endpoint: AdapterEndpoint) = newTransport()
    }

    @Test
    fun `connect reaches Connected with supported pids`() = runTest {
        val manager = ObdConnectionManager(listOf(factory { ScriptedTransport(FakeCar.replies) }), backgroundScope)
        manager.connect(endpoint)
        val state = manager.state.first { it is ConnectionState.Connected } as ConnectionState.Connected
        assertTrue(0x0C in state.supportedPids)
        assertEquals('6', state.info.protocolNumber)
    }

    @Test
    fun `adapter error ends in Failed with reason`() = runTest {
        val replies = FakeCar.replies + ("0100" to "UNABLE TO CONNECT")
        val manager = ObdConnectionManager(listOf(factory { ScriptedTransport(replies) }), backgroundScope)
        manager.connect(endpoint)
        val state = manager.state.first { it is ConnectionState.Failed } as ConnectionState.Failed
        assertTrue(state.reason, state.reason.contains("UNABLE TO CONNECT"))
    }

    @Test
    fun `transport that cannot open ends in Failed`() = runTest {
        val broken = object : ObdTransport {
            override suspend fun open() = throw IOException("нет сокета")
            override suspend fun write(data: ByteArray) = Unit
            override suspend fun read() = ByteArray(0)
            override suspend fun close() = Unit
        }
        val manager = ObdConnectionManager(listOf(factory { broken }), backgroundScope)
        manager.connect(endpoint)
        assertTrue(manager.state.first { it is ConnectionState.Failed } is ConnectionState.Failed)
    }

    @Test
    fun `readings are polled for selected pids`() = runTest {
        val manager = ObdConnectionManager(listOf(factory { ScriptedTransport(FakeCar.replies) }), backgroundScope)
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connected }
        manager.setPolledPids(listOf(StandardPids.byCode(0x0C)!!))
        assertEquals(1726.0, manager.readings.first().value, 0.001)
    }

    @Test
    fun `discover collects endpoints from all factories`() = runTest {
        val manager = ObdConnectionManager(listOf(factory { ScriptedTransport(FakeCar.replies) }), backgroundScope)
        assertEquals(listOf(endpoint), manager.discover())
    }
}

package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.AdapterProfile
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.FakeCar
import io.github.iampopovich.eculense.core.obd.ObdConnectionManager
import io.github.iampopovich.eculense.core.obd.ObdTransport
import io.github.iampopovich.eculense.core.obd.StandardPids
import io.github.iampopovich.eculense.core.obd.SwitchableTransport
import io.github.iampopovich.eculense.core.obd.TransportFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A3/A4 at the recorder: a reconnect keeps the recording, a stopped engine ends it cleanly. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderReconnectTest {
    private val endpoint = AdapterEndpoint("fake", "00:11", "Fake ELM")
    private val transports = mutableListOf<SwitchableTransport>()
    private val factory = object : TransportFactory {
        override val kind = "fake"
        override suspend fun discover() = listOf(endpoint)
        override fun create(endpoint: AdapterEndpoint): ObdTransport =
            SwitchableTransport(FakeCar.replies + ("010C1" to "410C1AF8")).apply { latencyMs = 20 }.also { transports += it }
    }

    @Test
    fun `recording continues into the same session after a reconnect`() = runTest {
        val manager = ObdConnectionManager(listOf(factory), backgroundScope, clock = { testScheduler.currentTime })
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connected }
        manager.setPolledPids(listOf(StandardPids.byCode(0x0C)!!))
        val repository = FakeSessionRepository()
        val recorder = LiveSessionRecorder(manager, repository, backgroundScope)
        recorder.start("Test car")
        delay(2_000)
        val beforeLoss = repository.appended.size
        assertTrue("$beforeLoss", beforeLoss > 0)

        transports[0].breakLink()
        manager.state.first { it is ConnectionState.Reconnecting }
        manager.state.first { it is ConnectionState.Connected }
        delay(3_000)
        assertTrue("${repository.appended.size} > $beforeLoss", repository.appended.size > beforeLoss)
        assertEquals(1L, recorder.activeSessionId.value)
        assertEquals(emptyList<Long>(), repository.finished)
        recorder.stop()
        runCurrent()
    }

    @Test
    fun `engine off ends the recording cleanly`() = runTest {
        val manager = ObdConnectionManager(listOf(factory), backgroundScope, clock = { testScheduler.currentTime })
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connected }
        manager.setPolledPids(listOf(StandardPids.byCode(0x0C)!!))
        val repository = FakeSessionRepository()
        val recorder = LiveSessionRecorder(manager, repository, backgroundScope)
        recorder.start("Test car")
        delay(1_000)
        val t = transports[0]
        // The recorder also polls what analytics needs (speed answers on FakeCar): the whole ECU goes silent.
        listOf("010C1", "010C", "010D1", "010D", "0100").forEach { t.replies[it] = "NO DATA" }
        t.replies["ATRV"] = "12.2V"
        manager.state.first { it is ConnectionState.EngineOff }
        runCurrent()
        assertEquals(listOf(1L), repository.finished)
        assertNull(recorder.activeSessionId.value)
        assertEquals(1, transports.size)
    }

    @Test
    fun `adapter profiles are stored per adapter and car`() {
        val profile = AdapterProfile(protocol = '5', unsupported = setOf("STI", "AT@1"), countSuffix = false)
        assertEquals(profile, DataStoreAdapterPrefs.decode(DataStoreAdapterPrefs.encode(profile)))
        assertEquals(AdapterProfile(), DataStoreAdapterPrefs.decode(DataStoreAdapterPrefs.encode(AdapterProfile())))
        assertNull(DataStoreAdapterPrefs.decode("garbage"))
    }
}

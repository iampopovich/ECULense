package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.RecordingLogger
import io.github.iampopovich.eculense.core.model.SensorReading
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1.1: adapter errors (ObdException, e.g. BUS INIT ERROR) thrown by the ATRV / 0100 checks after a stall must end
 * in Reconnecting/Failed, never escape into the app scope. runTest fails on any uncaught exception in backgroundScope.
 */
class StallErrorHandlingTest {
    private val endpoint = AdapterEndpoint("fake", "00:11", "Fake ELM")
    private val rpm = StandardPids.byCode(0x0C)!!

    /** No PID ever answers (the poller stalls); [voltage] / [ecu] decide what the stall checks do. */
    private class StallingSession(
        val voltage: suspend (Int) -> Double?,
        val ecu: suspend (Int) -> Boolean,
    ) : ObdSession {
        var voltageCalls = 0
        var ecuCalls = 0
        override suspend fun initialize() = AdapterInfo("fake", '6', true, 12.5)
        override suspend fun supportedPids() = setOf(0x0C)
        override suspend fun read(pid: Pid): SensorReading? = null.also { delay(10) }
        override suspend fun readDtcs(kind: DtcKind) = emptyList<Dtc>()
        override suspend fun clearDtcs() = false
        override suspend fun readVin(): String? = null
        override suspend fun batteryVoltage(): Double? = voltage(voltageCalls++)
        override suspend fun ecuResponds(): Boolean = ecu(ecuCalls++)
    }

    private fun TestScope.manager(session: () -> ObdSession) = ObdConnectionManager(
        factories = listOf(object : TransportFactory {
            override val kind = "fake"
            override suspend fun discover() = listOf(endpoint)
            override fun create(endpoint: AdapterEndpoint): ObdTransport = SwitchableTransport(FakeCar.replies)
        }),
        scope = backgroundScope,
        sessionFactory = { _, _ -> session() },
        clock = { testScheduler.currentTime },
        engineOffCheckMs = 1_000,
        giveUpMs = 3_000,
    )

    private suspend fun TestScope.runStall(manager: ObdConnectionManager): List<ConnectionState> {
        val states = mutableListOf<ConnectionState>()
        backgroundScope.launch { manager.state.collect { states += it } }
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connected }
        manager.setPolledPids(listOf(rpm))
        backgroundScope.launch { manager.readings.collect { } }
        manager.state.first { it is ConnectionState.Reconnecting || it is ConnectionState.Failed }
        delay(30_000)
        return states
    }

    private val busInit = ObdException(ObdError.Other("BUS INIT: ...ERROR"))

    @Test
    fun `ATRV throwing an adapter error after a stall reconnects instead of crashing`() = runTest {
        val states = runStall(manager { StallingSession(voltage = { throw busInit }, ecu = { true }) })
        assertTrue(states.toString(), states.any { it is ConnectionState.Reconnecting })
    }

    @Test
    fun `0100 throwing an adapter error after a stall reconnects instead of crashing`() = runTest {
        val states = runStall(manager { StallingSession(voltage = { 12.4 }, ecu = { throw busInit }) })
        assertTrue(states.toString(), states.any { it is ConnectionState.Reconnecting })
    }

    @Test
    fun `adapter error while waiting for the engine reconnects instead of crashing`() = runTest {
        // First check: adapter alive, ECU silent -> EngineOff; the next ATRV throws.
        val states = runStall(
            manager {
                StallingSession(voltage = { n -> if (n == 0) 12.2 else throw busInit }, ecu = { false })
            },
        )
        assertTrue(states.toString(), states.any { it is ConnectionState.EngineOff })
        assertTrue(states.toString(), states.any { it is ConnectionState.Reconnecting })
    }

    @Test
    fun `a runtime error from the stall checks also ends in reconnecting`() = runTest {
        val states = runStall(manager { StallingSession(voltage = { throw IllegalStateException("parser") }, ecu = { true }) })
        assertTrue(states.toString(), states.any { it is ConnectionState.Reconnecting })
    }

    @Test
    fun `app scope logs an uncaught child failure and keeps running`() {
        val logger = RecordingLogger()
        val scope = appScope(logger)
        runBlocking {
            scope.launch { throw IllegalStateException("boom") }.join()
            val second = scope.async { 42 }
            assertEquals(42, second.await())
        }
        val entry = logger.entries.single()
        assertEquals("EcuAppScope", entry.tag)
        assertTrue(entry.error is IllegalStateException)
        scope.cancel()
    }
}

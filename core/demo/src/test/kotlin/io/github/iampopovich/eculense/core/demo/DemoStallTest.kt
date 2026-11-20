package io.github.iampopovich.eculense.core.demo

import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.ObdConnectionManager
import io.github.iampopovich.eculense.core.obd.StandardPids
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** B10: «Demo: engine stops» stalls (rpm 0 with the ECU answering) for 3 s before the engine goes silent. */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoStallTest {
    @Test
    fun `rpm reads 0 while the ecu still answers just before the engine is switched off`() = runTest {
        val factory = EngineStopDemoTransportFactory(clock = { testScheduler.currentTime }, latencyMs = { 10L })
        val manager = ObdConnectionManager(listOf(factory), backgroundScope, clock = { testScheduler.currentTime })
        manager.connect(EngineStopDemoTransportFactory.ENDPOINT)
        manager.state.first { it is ConnectionState.Connected }
        val rpm = StandardPids.byCode(0x0C)!!
        advanceTimeBy(15_000)
        assertTrue(manager.withSession { it.read(rpm) }!!.value > 400)
        advanceTimeBy(13_000) // 28 s after connecting: inside the stall window
        assertEquals(0.0, manager.withSession { it.read(rpm) }!!.value, 0.0)
    }
}

package io.github.iampopovich.eculense.core.demo

import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.ObdConnectionManager
import io.github.iampopovich.eculense.core.obd.StandardPids
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Demo adapter with a fixed 45 ms latency (virtual time): cycle speed with multi-PID (A7/A8) and engine off (A4). */
class DemoCycleTimeTest {
    private val pids = StandardPids.dashboardDefaults.mapNotNull { StandardPids.byCode(it) }

    @Test
    fun `dashboard cycle uses multi-PID requests on the CAN demo`() = runTest {
        val factory = DemoTransportFactory(clock = { testScheduler.currentTime }, latencyMs = { 45L })
        val manager = ObdConnectionManager(listOf(factory), backgroundScope, clock = { testScheduler.currentTime })
        manager.connect(DemoTransportFactory.ENDPOINT)
        manager.state.first { it is ConnectionState.Connected }
        manager.setPolledPids(pids)
        val cycles = 10
        val started = testScheduler.currentTime
        val readings = manager.readings.take(pids.size * cycles).toList()
        val perCycle = (testScheduler.currentTime - started) / cycles
        println("DEMO_CYCLE_MS=$perCycle for ${pids.size} PIDs; stats=${manager.pollStats.value}")
        // 8 single requests would take 8 × 45 = 360 ms; two multi-PID requests take 90 ms.
        assertEquals(90L, perCycle)
        assertEquals(90L, manager.pollStats.value!!.cycleMsP50)
        assertEquals(pids.map { it.sensorId }.toSet(), readings.map { it.sensorId }.toSet())
    }

    @Test
    fun `engine stop demo goes to EngineOff and resumes`() = runTest {
        val factory = EngineStopDemoTransportFactory(clock = { testScheduler.currentTime }, latencyMs = { 45L }, offWindow = 5_000L..20_000L)
        val manager = ObdConnectionManager(listOf(factory), backgroundScope, clock = { testScheduler.currentTime })
        manager.connect(EngineStopDemoTransportFactory.ENDPOINT)
        manager.state.first { it is ConnectionState.Connected }
        manager.setPolledPids(pids)
        val readings = mutableListOf<Any>()
        backgroundScope.launch { manager.readings.collect { readings += it } }
        val off = manager.state.first { it is ConnectionState.EngineOff } as ConnectionState.EngineOff
        assertEquals(12.3, off.voltage!!, 0.0)
        manager.state.first { it is ConnectionState.Connected }
        val before = readings.size
        manager.readings.first()
        assertTrue(testScheduler.currentTime >= 20_000)
        assertTrue(readings.size >= before)
    }
}

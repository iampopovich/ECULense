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
        val readings = mutableListOf<io.github.iampopovich.eculense.core.model.SensorReading>()
        val job = launch { manager.readings.collect { readings += it } }
        kotlinx.coroutines.delay(3_000)
        job.cancel()
        val rpm = readings.count { it.sensorId == "01.0C" }
        println("DEMO rpm readings in 3 s: $rpm; stats=${manager.pollStats.value}")
        // C2 + A8: the four fast PIDs plus whatever slower PID is due fit into one multi-PID request (45 ms); round
        // robin over 8 single requests took 360 ms per rpm value.
        assertEquals(45L, manager.pollStats.value!!.cycleMsP50)
        assertTrue("rpm=$rpm", rpm >= 3_000 / 60)
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

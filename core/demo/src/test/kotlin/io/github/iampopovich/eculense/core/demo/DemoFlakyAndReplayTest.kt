package io.github.iampopovich.eculense.core.demo

import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.ObdConnectionManager
import io.github.iampopovich.eculense.core.obd.ObdException
import io.github.iampopovich.eculense.core.obd.ObdTransport
import io.github.iampopovich.eculense.core.obd.ReplayTransportFactory
import io.github.iampopovich.eculense.core.obd.RingTrafficLog
import io.github.iampopovich.eculense.core.obd.StandardPids
import io.github.iampopovich.eculense.core.obd.TrafficDirection
import io.github.iampopovich.eculense.core.obd.TransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class DemoFlakyAndReplayTest {
    private val rpm = StandardPids.byCode(0x0C)!!

    private fun TestScope.flakyFactory(lateScope: CoroutineScope) = object : TransportFactory {
        override val kind = FlakyDemoTransportFactory.KIND
        override suspend fun discover() = listOf(FlakyDemoTransportFactory.ENDPOINT)
        override fun create(endpoint: AdapterEndpoint): ObdTransport =
            DemoTransport({ testScheduler.currentTime }, { 30L }, DemoEngine(), flaky = true, lateScope = lateScope)
    }

    private suspend fun ObdConnectionManager.connectTo(endpoint: AdapterEndpoint): ConnectionState.Connected {
        connect(endpoint)
        val state = state.first { it is ConnectionState.Connected || it is ConnectionState.Failed }
        assertTrue("expected Connected, got $state", state is ConnectionState.Connected)
        return state as ConnectionState.Connected
    }

    @Test
    fun `flaky clone connects through the protocol fallback and live reads recover from late replies`() = runTest {
        val log = RingTrafficLog(clock = { testScheduler.currentTime })
        val manager = ObdConnectionManager(listOf(flakyFactory(backgroundScope)), backgroundScope, log)
        val state = manager.connectTo(FlakyDemoTransportFactory.ENDPOINT)
        assertEquals("ELM327 v1.5", state.info.version)
        assertEquals('6', state.info.protocolNumber)
        assertTrue(state.info.isCan)

        val values = manager.withSession { session ->
            List(3 * DemoTransport.LATE_EVERY) {
                try {
                    session.read(rpm)?.value
                } catch (e: ObdException) {
                    null
                }
            }
        }!!
        val misses = values.withIndex().filter { it.value == null }.map { it.index }
        assertEquals("one miss per late reply", 3, misses.size)
        misses.forEach { i -> if (i + 1 < values.size) assertTrue("read after a late reply #$i", values[i + 1] != null) }
        values.filterNotNull().forEach { assertTrue("rpm=$it", it in 600.0..7000.0) }
        assertTrue(log.snapshot().any { it.direction == TrafficDirection.DROP })
    }

    @Test
    fun `recorded demo session replays with the same readings`() = runTest {
        val log = RingTrafficLog(clock = { testScheduler.currentTime })
        val demo = DemoTransportFactory(clock = { testScheduler.currentTime }, latencyMs = { 0L })
        val recorder = ObdConnectionManager(listOf(demo), backgroundScope, log)
        val recordedState = recorder.connectTo(DemoTransportFactory.ENDPOINT)
        val pids = StandardPids.all.filter { it.code in recordedState.supportedPids }
        val recorded = recorder.withSession { session ->
            val first = pids.map { session.read(it)?.value }
            delay(15_000) // the demo car moves on, the second pass differs from the first
            val second = pids.map { session.read(it)?.value }
            Triple(first, second, session.readDtcs(DtcKind.STORED))
        }!!
        recorder.disconnect()
        assertFalse(recorded.first == recorded.second)

        val exported = log.export(ZoneOffset.UTC)
        val replay = ReplayTransportFactory("Replay") { exported }
        val player = ObdConnectionManager(listOf(replay), backgroundScope)
        val endpoint = replay.discover().single()
        val replayedState = player.connectTo(endpoint)
        assertEquals(recordedState.info, replayedState.info)
        assertEquals(recordedState.supportedPids, replayedState.supportedPids)
        val replayed = player.withSession { session ->
            Triple(pids.map { session.read(it)?.value }, pids.map { session.read(it)?.value }, session.readDtcs(DtcKind.STORED))
        }!!
        assertEquals(recorded, replayed)
    }
}

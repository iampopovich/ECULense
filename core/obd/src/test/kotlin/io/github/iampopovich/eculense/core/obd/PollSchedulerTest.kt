package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.SensorReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** C2: priority tiers instead of plain round robin. */
@OptIn(ExperimentalCoroutinesApi::class)
class PollSchedulerTest {
    private fun pids(vararg codes: Int) = codes.map { StandardPids.byCode(it)!! }

    @Test
    fun `tiers come from the data table`() {
        assertEquals(PollTier.FAST, PollTiers.tierOf(0x0C))
        assertEquals(PollTier.FAST, PollTiers.tierOf(0x11))
        assertEquals(PollTier.MEDIUM, PollTiers.tierOf(0x10))
        assertEquals(PollTier.MEDIUM, PollTiers.tierOf(0x14))
        assertEquals(PollTier.SLOW, PollTiers.tierOf(0x05))
        assertEquals(PollTier.SLOW, PollTiers.tierOf(0x07))
        assertEquals(PollTier.SLOW, PollTiers.tierOf(0x42))
        assertEquals(PollTier.SLOW, PollTiers.tierOf(0x99))
    }

    @Test
    fun `first cycle reads everything, then each tier at its period`() {
        val scheduler = PollScheduler()
        val list = pids(0x0C, 0x0D, 0x10, 0x05)
        assertEquals(list, scheduler.due(0, list))
        val counts = mutableMapOf<Int, Int>()
        for (cycle in 1L..30L) scheduler.due(cycle, list).forEach { counts.merge(it.code, 1, Int::plus) }
        assertEquals(30, counts[0x0C])
        assertEquals(30, counts[0x0D])
        assertEquals(10, counts[0x10])
        assertEquals(3, counts[0x05])
    }

    @Test
    fun `slow pids are spread over the period instead of one long cycle`() {
        val scheduler = PollScheduler()
        val slow = pids(0x05, 0x06, 0x07, 0x2F, 0x33)
        val perCycle = (1L..10L).map { scheduler.due(it, slow).size }
        assertEquals(5, perCycle.sum())
        assertTrue("$perCycle", perCycle.all { it <= 1 })
    }

    @Test
    fun `background doubles medium and slow periods but keeps fast`() {
        val scheduler = PollScheduler()
        val list = pids(0x0C, 0x10, 0x05)
        val counts = mutableMapOf<Int, Int>()
        for (cycle in 1L..60L) scheduler.due(cycle, list, background = true).forEach { counts.merge(it.code, 1, Int::plus) }
        assertEquals(60, counts[0x0C])
        assertEquals(10, counts[0x10])
        assertEquals(3, counts[0x05])
    }

    /** K-Line: every PID costs 280 ms, no multi-PID requests. */
    private class KLineSession(private val perPidMs: Long = 280) : ObdSession {
        override suspend fun initialize() = error("not used")
        override suspend fun supportedPids() = StandardPids.all.map { it.code }.toSet()
        override suspend fun read(pid: Pid): SensorReading {
            delay(perPidMs)
            return SensorReading(pid.sensorId, 1.0, pid.unit, 0)
        }
        override suspend fun readDtcs(kind: io.github.iampopovich.eculense.core.model.DtcKind) = emptyList<io.github.iampopovich.eculense.core.model.Dtc>()
        override suspend fun clearDtcs() = false
        override suspend fun readVin(): String? = null
        override suspend fun batteryVoltage(): Double = 13.9
    }

    private class RoundRobin : PollScheduler() {
        override fun due(cycle: Long, pids: List<Pid>, background: Boolean) = pids
    }

    private fun TestScope.countReadings(list: List<Pid>, scheduler: PollScheduler, windowMs: Long): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        val job = backgroundScope.launch {
            LivePoller(KLineSession(), clock = { testScheduler.currentTime }, scheduler = scheduler).poll(list)
                .collect { counts.merge(it.sensorId, 1, Int::plus) }
        }
        advanceTimeBy(windowMs)
        runCurrent()
        job.cancel()
        return counts
    }

    @Test
    fun `on a K-Line car rpm and speed refresh at least 3x more often with the car data set`() = runTest {
        // What the car data screen polls on a car that supports every known PID.
        val list = StandardPids.all
        val before = countReadings(list, RoundRobin(), 120_000)
        val after = countReadings(list, PollScheduler(), 120_000)
        for (id in listOf("01.0C", "01.0D")) {
            val ratio = after.getValue(id).toDouble() / before.getValue(id)
            assertTrue("$id: ${before[id]} -> ${after[id]} ($ratio x)", ratio >= 3.0)
        }
        // Every PID still gets read, at least at the slow rate (10 cycles of ~2.5 s).
        list.forEach { assertTrue("${it.sensorId}: ${after[it.sensorId]}", (after[it.sensorId] ?: 0) >= 3) }
    }

    @Test
    fun `dashboard plus recording set on K-Line gets faster too`() = runTest {
        val list = pids(0x0C, 0x0D, 0x05, 0x04, 0x11, 0x42, 0x0F, 0x10, 0x06, 0x07, 0x2F)
        val before = countReadings(list, RoundRobin(), 120_000)
        val after = countReadings(list, PollScheduler(), 120_000)
        val ratio = after.getValue("01.0C").toDouble() / before.getValue("01.0C")
        assertTrue("rpm ${before["01.0C"]} -> ${after["01.0C"]} ($ratio x)", ratio >= 1.8)
    }

    @Test
    fun `adapter voltage is read about every 10 s`() = runTest {
        val counts = countReadings(pids(0x0C), PollScheduler(), 35_000)
        assertEquals(3, counts[PollTiers.ADAPTER_VOLTAGE_ID])
    }

    @Test
    fun `background flag is read every cycle without restarting the poller`() = runTest {
        val background = MutableStateFlow(false)
        val counts = mutableMapOf<String, Int>()
        backgroundScope.launch {
            LivePoller(KLineSession(100), clock = { testScheduler.currentTime }, background = background, voltageEveryMs = 0)
                .poll(pids(0x0C, 0x05)).collect { counts.merge(it.sensorId, 1, Int::plus) }
        }
        advanceTimeBy(20_000)
        val foreground = counts["01.05"] ?: 0
        background.value = true
        counts.clear()
        advanceTimeBy(20_000)
        val inBackground = counts["01.05"] ?: 0
        assertTrue("$foreground vs $inBackground", inBackground < foreground * 0.75)
    }
}

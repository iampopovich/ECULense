package io.github.iampopovich.eculense.core.demo

import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.MidGroup
import io.github.iampopovich.eculense.core.obd.ObdConnectionManager
import io.github.iampopovich.eculense.core.obd.ObdDiagnostics
import io.github.iampopovich.eculense.core.obd.RecentClearVerdict
import io.github.iampopovich.eculense.core.obd.UsedCarCheck
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The demo answers the diagnostics reads (B1-B4, B7, B8) through the real :core:obd stack. */
class DemoDiagnosticsTest {
    private suspend fun ObdConnectionManager.demo(): ConnectionState.Connected {
        connect(DemoTransportFactory.ENDPOINT)
        return state.first { it is ConnectionState.Connected || it is ConnectionState.Failed } as ConnectionState.Connected
    }

    @Test
    fun `readiness, freeze frame, counters, mode 06 and vehicle info before and after a clear`() = runTest {
        val manager = ObdConnectionManager(listOf(DemoTransportFactory(clock = { testScheduler.currentTime }, latencyMs = { 1 })), backgroundScope)
        val connected = manager.demo()
        val before = manager.withSession { s ->
            val d = ObdDiagnostics(s)
            val readiness = d.readiness()!!
            Triple(readiness, d.freezeFrame(), UsedCarCheck.evaluate(d.counters(connected.supportedPids), readiness))
        }!!
        assertTrue(before.first.milOn)
        assertEquals(2, before.first.dtcCount)
        assertTrue(before.first.allComplete)
        val ff = before.second!!
        assertEquals("P0133", ff.dtcCode)
        assertTrue(ff.value("01.0C")!! > 700)
        assertTrue(ff.value("01.0D")!! > 30)
        assertEquals(RecentClearVerdict.NO_SIGNS, before.third.verdict)

        val mode06 = manager.withSession { ObdDiagnostics(it).mode06() }!!
        assertEquals(6, mode06.size)
        assertEquals(1, mode06.count { !it.passed })
        assertTrue(mode06.any { it.group == MidGroup.CATALYST })
        val info = manager.withSession { ObdDiagnostics(it).vehicleInfo() }!!
        assertEquals(DemoEngine.VIN, info.vin)
        assertEquals(listOf("DEMOCAL000000001"), info.calibrationIds)
        assertEquals(listOf("1A2B3C4D"), info.cvns)

        assertTrue(manager.withSession { it.clearDtcs() }!!)
        val after = manager.withSession { s ->
            val d = ObdDiagnostics(s)
            val readiness = d.readiness()!!
            Triple(readiness, d.freezeFrame(), UsedCarCheck.evaluate(d.counters(connected.supportedPids), readiness))
        }!!
        assertFalse(after.first.milOn)
        assertEquals(3, after.first.incompleteCount)
        assertNull(after.second)
        assertEquals(RecentClearVerdict.LIKELY_CLEARED, after.third.verdict)
        assertEquals(0, after.third.counters.warmUpsSinceClear)
    }
}

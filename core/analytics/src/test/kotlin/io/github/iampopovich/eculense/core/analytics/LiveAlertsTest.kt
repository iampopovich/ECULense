package io.github.iampopovich.eculense.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAlertEvaluatorTest {
    private val e = LiveAlertEvaluator()

    @Test
    fun `default thresholds reuse the analytics numbers`() {
        val c = LiveAlertConfig()
        assertEquals(Thresholds.OVERHEAT_CRIT_C, c.overheat.value, 0.0)
        assertEquals(12.0, c.notCharging.value, 0.0)
        assertEquals(15.2, c.overcharge.value, 0.0)
        assertTrue(c.newDtc)
    }

    @Test
    fun `overheat needs 5 s, clears with hysteresis and notifies once per cooldown`() {
        assertTrue(e.onReading("01.05", 111.0, 0).isEmpty())
        val up = e.onReading("01.05", 112.0, 5_000).single()
        assertTrue(up.raised && up.notify)
        assertTrue(e.onReading("01.05", 108.5, 6_000).isEmpty())
        assertTrue(LiveAlertKind.OVERHEAT in e.activeKinds)
        val down = e.onReading("01.05", 106.9, 7_000).single()
        assertFalse(down.raised)
        e.onReading("01.05", 111.0, 8_000)
        val again = e.onReading("01.05", 111.0, 13_000).single()
        assertTrue(again.raised)
        assertFalse("cooldown: no second heads-up", again.notify)
        e.onReading("01.05", 100.0, 14_000)
        e.onReading("01.05", 111.0, 700_000)
        assertTrue(e.onReading("01.05", 111.0, 705_000).single().notify)
    }

    @Test
    fun `a single outlier is not an alert`() {
        e.onReading("01.05", 115.0, 0)
        e.onReading("01.05", 95.0, 2_000)
        assertTrue(e.onReading("01.05", 115.0, 6_000).isEmpty())
    }

    @Test
    fun `not charging only while the engine runs above 500 rpm`() {
        e.onReading("elm.voltage", 11.6, 0)
        assertTrue(e.onReading("elm.voltage", 11.6, 6_000).isEmpty())
        e.onReading("01.0C", 800.0, 6_000)
        e.onReading("elm.voltage", 11.6, 7_000)
        val up = e.onReading("elm.voltage", 11.5, 12_000)
        assertEquals(listOf(LiveAlertKind.NOT_CHARGING), up.filter { it.raised }.map { it.kind })
        val idleDrop = e.onReading("01.0C", 0.0, 13_000)
        assertEquals(LiveAlertKind.NOT_CHARGING, idleDrop.single().kind)
        assertFalse(idleDrop.single().raised)
    }

    @Test
    fun `module voltage wins over the adapter voltage`() {
        e.onReading("01.42", 14.1, 0)
        e.onReading("elm.voltage", 15.9, 1_000)
        assertTrue(e.onReading("elm.voltage", 15.9, 7_000).isEmpty())
        e.onReading("01.42", 15.5, 8_000)
        assertEquals(LiveAlertKind.OVERCHARGE, e.onReading("01.42", 15.6, 13_000).single().kind)
    }

    @Test
    fun `a switched off threshold never alerts and clears an active one`() {
        e.onReading("01.05", 120.0, 0); e.onReading("01.05", 120.0, 5_000)
        e.config = e.config.copy(overheat = AlertThreshold(false, 110.0))
        assertFalse(e.onReading("01.05", 120.0, 6_000).single().raised)
        assertTrue(e.onReading("01.05", 120.0, 20_000).isEmpty())
    }

    @Test
    fun `a lowered threshold applies at once`() {
        e.config = e.config.with(LiveAlertKind.OVERHEAT, AlertThreshold(true, 60.0))
        e.onReading("01.05", 70.0, 0)
        assertTrue(e.onReading("01.05", 70.0, 5_000).single().raised)
    }
}

class NewDtcTrackerTest {
    @Test
    fun `codes present at connect never alert, a new one alerts once`() {
        val t = NewDtcTracker()
        assertTrue(t.onRead(listOf("P0133", "P0300")).isEmpty())
        assertTrue(t.onRead(listOf("P0133", "P0300")).isEmpty())
        assertEquals(listOf("P0171"), t.onRead(listOf("P0133", "P0300", "P0171")))
        assertTrue(t.onRead(listOf("P0171")).isEmpty())
        t.reset()
        assertTrue(t.onRead(listOf("P0420")).isEmpty())
    }
}

class StallDetectorTest {
    private val d = StallDetector()

    @Test
    fun `rpm to zero shortly after driving with ignition on is a stall, once`() {
        d.onReading("elm.voltage", 12.3, 0)
        d.onReading("01.0D", 15.0, 1_000)
        d.onReading("01.0C", 900.0, 1_000)
        assertTrue(d.onReading("01.0C", 0.0, 4_000))
        assertFalse(d.onReading("01.0C", 0.0, 5_000))
        d.onReading("01.0C", 850.0, 20_000)
        assertTrue(d.onReading("01.0C", 0.0, 22_000))
    }

    @Test
    fun `zero long after the engine ran or with a dead port is not a stall`() {
        d.onReading("01.0C", 900.0, 0)
        assertFalse(d.onReading("01.0C", 0.0, 11_000))
        val e = StallDetector()
        e.onReading("01.0C", 900.0, 0)
        e.onReading("elm.voltage", 3.0, 500)
        assertFalse(e.onReading("01.0C", 0.0, 1_000))
    }

    @Test
    fun `after a reset the next zero is not a stall`() {
        d.onReading("01.0C", 900.0, 0)
        d.reset()
        assertFalse(d.onReading("01.0C", 0.0, 1_000))
    }
}

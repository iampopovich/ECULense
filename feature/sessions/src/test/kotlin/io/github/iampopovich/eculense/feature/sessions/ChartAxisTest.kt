package io.github.iampopovich.eculense.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartAxisTest {
    @Test
    fun `x step gives about six labels on a round number of seconds`() {
        assertEquals(1.0, niceXStepSeconds(0.0), 0.0)
        assertEquals(1.0, niceXStepSeconds(4.2), 0.0)
        assertEquals(5.0, niceXStepSeconds(28.0), 0.0)
        assertEquals(10.0, niceXStepSeconds(60.0), 0.0)
        assertEquals(600.0, niceXStepSeconds(3600.0), 0.0)
    }

    @Test
    fun `chart starts on engine rpm when it was recorded`() {
        assertEquals("01.0C", defaultSensor(listOf("01.04", "01.0C", "01.0D")))
        assertEquals("01.04", defaultSensor(listOf("01.04", "01.0D")))
        assertEquals(null, defaultSensor(emptyList()))
    }
}

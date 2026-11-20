package io.github.iampopovich.eculense.feature.sessions

import io.github.iampopovich.eculense.core.model.SensorReading
import org.junit.Assert.assertEquals
import org.junit.Test

class EventWindowTest {
    private val points = (0..10).map { SensorReading("01.0C", it.toDouble(), "rpm", it * 60_000L) }

    @Test
    fun `an event shows 2 min before and after it`() {
        assertEquals(listOf(3, 4, 5, 6, 7), windowed(points, 5 * 60_000L).map { it.value.toInt() })
        assertEquals(11, windowed(points, null).size)
    }

    @Test
    fun `offset from the recording start`() {
        assertEquals("+3:12", offsetLabel(1_000L + 192_000L, 1_000L))
        assertEquals("+0:00", offsetLabel(5L, null))
    }
}

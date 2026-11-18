package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LivePollerTest {
    private val rpm = StandardPids.byCode(0x0C)!!
    private val speed = StandardPids.byCode(0x0D)!!

    @Test
    fun `polls round robin`() = runTest {
        val session = FakeSession(mapOf(0x0C to 800.0, 0x0D to 0.0))
        val ids = LivePoller(session).poll(listOf(rpm, speed)).take(4).toList().map { it.sensorId }
        assertEquals(listOf("01.0C", "01.0D", "01.0C", "01.0D"), ids)
    }

    @Test
    fun `failing pid is skipped and others keep flowing`() = runTest {
        val session = FakeSession(mapOf(0x0C to 800.0, 0x0D to 0.0), failing = setOf(0x0D))
        val ids = LivePoller(session).poll(listOf(rpm, speed)).take(2).toList().map { it.sensorId }
        assertEquals(listOf("01.0C", "01.0C"), ids)
    }
}

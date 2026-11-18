package io.github.iampopovich.eculense.core.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HexTest {
    @Test
    fun `hex to bytes`() {
        assertArrayEquals(byteArrayOf(0x41, 0x0C, 0x1A, 0xF8.toByte()), "410C1AF8".hexToBytes())
    }

    @Test
    fun `single frame lines are separate messages`() {
        val messages = elmMessages(listOf("4100BE3FA813", "410098180001"))
        assertEquals(2, messages.size)
    }

    @Test
    fun `CAN multi-frame is merged and trimmed to declared length`() {
        val lines = listOf("014", "0:490201314434", "1:47503030523535", "2:42313233343536")
        val message = elmMessages(lines).single()
        assertEquals(0x14, message.size)
        assertEquals("1D4GP00R55B123456", message.drop(3).map { it.toInt().toChar() }.joinToString(""))
    }

    @Test
    fun `non-hex lines are ignored`() {
        assertEquals(1, elmMessages(listOf("ELM327v1.5", "410D20")).size)
    }
}

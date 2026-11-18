package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind.STORED
import org.junit.Assert.assertEquals
import org.junit.Test

class DtcDecoderTest {
    @Test
    fun `two bytes to code`() {
        assertEquals("P0133", DtcDecoder.code(0x01, 0x33))
        assertEquals("C0123", DtcDecoder.code(0x41, 0x23))
        assertEquals("U0155", DtcDecoder.code(0xC1, 0x55))
        assertEquals("P0420", DtcDecoder.code(0x04, 0x20))
    }

    @Test
    fun `CAN reply has a count byte`() {
        val result = DtcDecoder.parse(elmMessages(listOf("430201330300")), 0x43, isCan = true, kind = STORED)
        assertEquals(listOf(Dtc("P0133", STORED), Dtc("P0300", STORED)), result)
    }

    @Test
    fun `K-Line reply has no count byte and zero padding`() {
        val result = DtcDecoder.parse(elmMessages(listOf("43013303000000")), 0x43, isCan = false, kind = STORED)
        assertEquals(listOf(Dtc("P0133", STORED), Dtc("P0300", STORED)), result)
    }

    @Test
    fun `CAN multi-frame with four codes`() {
        val lines = listOf("00A", "0:430401330300", "1:0420C1550000")
        val codes = DtcDecoder.parse(elmMessages(lines), 0x43, isCan = true, kind = STORED).map { it.code }
        assertEquals(listOf("P0133", "P0300", "P0420", "U0155"), codes)
    }

    @Test
    fun `no codes`() {
        assertEquals(emptyList<Dtc>(), DtcDecoder.parse(elmMessages(listOf("4300")), 0x43, isCan = true, kind = STORED))
    }
}

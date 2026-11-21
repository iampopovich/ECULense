package io.github.iampopovich.eculense.core.obdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R-E: fmt fields on synthetic payloads. */
class SignalDecoderTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `bit index, length, sign, scaling, little-endian bytes, null ranges and maps`() {
        val data = bytes(0xFF, 0xF7, 0x80, 0x01, 0x34, 0x12)
        assertEquals(255.0, SignalDecoder.decode(SignalFormat(bix = 0, len = 8), data)!!.value, 0.0)
        assertEquals(-9.0, SignalDecoder.decode(SignalFormat(bix = 8, len = 8, signed = true), data)!!.value, 0.0)
        assertEquals(1.0, SignalDecoder.decode(SignalFormat(bix = 16, len = 1), data)!!.value, 0.0) // MSB of byte 2
        assertEquals(0.0, SignalDecoder.decode(SignalFormat(bix = 17, len = 1), data)!!.value, 0.0)
        assertEquals(0x8001 / 10.0 - 40, SignalDecoder.decode(SignalFormat(bix = 16, len = 16, div = 10.0, add = -40.0), data)!!.value, 1e-9)
        assertEquals(0x1234.toDouble(), SignalDecoder.decode(SignalFormat(bix = 32, len = 16, blsb = true), data)!!.value, 0.0)
        assertEquals(7.0, SignalDecoder.decode(SignalFormat(bix = 12, len = 4), data)!!.value, 0.0) // low nibble of F7
        assertNull(SignalDecoder.decode(SignalFormat(bix = 0, len = 8, nullMax = 255.0), data))
        assertNull(SignalDecoder.decode(SignalFormat(bix = 0, len = 8, max = 100.0), data))
        assertNull(SignalDecoder.decode(SignalFormat(bix = 40, len = 16), data)) // past the end
        assertEquals("on", SignalDecoder.decode(SignalFormat(bix = 24, len = 8, map = mapOf(1L to "on")), data)!!.label)
    }

    @Test
    fun `payload drops the positive echo of 22 and 21, other replies give nothing`() {
        val c22 = SignalCommand("7E4", "7EC", "22", "0101", null, null, null, emptyList())
        val c21 = SignalCommand("7E0", null, "21", "01", null, null, null, emptyList())
        assertEquals(listOf(0xAA), SignalDecoder.payload(c22, bytes(0x62, 0x01, 0x01, 0xAA))!!.map { it.toInt() and 0xFF })
        assertEquals(listOf(0xBB), SignalDecoder.payload(c21, bytes(0x61, 0x01, 0xBB))!!.map { it.toInt() and 0xFF })
        assertNull(SignalDecoder.payload(c22, bytes(0x62, 0x01, 0x02, 0xAA)))
        assertNull(SignalDecoder.payload(c22, bytes(0x7F, 0x22, 0x31)))
    }
}

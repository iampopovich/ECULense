package io.github.iampopovich.eculense.core.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-D: ATH1 reply vectors (ELM327 datasheet examples, obd2lib, synthetic K-Line multi-ECU in the Sorento shape). */
class HeaderedResponseParserTest {
    private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    @Test
    fun `CAN 11 bit datasheet example with and without spaces`() {
        for (line in listOf("7E8 06 41 00 BE 3F B8 13 00", "7E8064100BE3FB81300")) {
            val f = HeaderedResponseParser.frame(line, HeaderFormat.CAN_11)!!
            assertEquals("7E8", f.source)
            assertEquals(0x06, f.pci)
            assertNull(f.checksumOk)
            val reply = HeaderedResponseParser.parse(listOf(line), HeaderFormat.CAN_11)
            // The PCI length (6) cuts the padding byte 00.
            assertEquals("41 00 BE 3F B8 13", reply.byEcu.getValue("7E8").single().hex())
            assertTrue(reply.rejected.isEmpty())
        }
    }

    @Test
    fun `CAN 11 bit multi-frame messages are assembled per ECU even when interleaved`() {
        val lines = listOf(
            "7E8 10 14 49 02 01 31 44 34",
            "7E9 06 41 00 80 00 00 01 00",
            "7E8 21 47 50 30 30 52 35 35",
            "7E8 22 42 31 32 33 34 35 36",
        )
        val reply = HeaderedResponseParser.parse(lines, HeaderFormat.CAN_11)
        assertEquals(listOf("7E8", "7E9"), reply.byEcu.keys.toList().sorted())
        val vin = reply.byEcu.getValue("7E8").single()
        assertEquals(20, vin.size)
        assertEquals("1D4GP00R55B123456", String(vin.copyOfRange(3, 20).map { it.toInt().toChar() }.toCharArray()))
        assertEquals("41 00 80 00 00 01", reply.byEcu.getValue("7E9").single().hex())
    }

    @Test
    fun `a consecutive frame out of sequence rejects the whole message`() {
        val reply = HeaderedResponseParser.parse(listOf("7E8 10 14 49 02 01 31 44 34", "7E8 22 42 31 32 33 34 35 36"), HeaderFormat.CAN_11)
        assertTrue(reply.byEcu.isEmpty())
        assertEquals(2, reply.rejected.size)
    }

    @Test
    fun `CAN 29 bit splits priority, target and source`() {
        for (line in listOf("18 DA F1 10 06 41 00 BE 3F B8 13 00", "18DAF110064100BE3FB81300")) {
            val f = HeaderedResponseParser.frame(line, HeaderFormat.CAN_29)!!
            assertEquals("18DAF110", f.source)
            assertEquals("F1", f.target)
            assertEquals("41 00 BE 3F B8 13", HeaderedResponseParser.parse(listOf(line), HeaderFormat.CAN_29).byEcu.getValue("18DAF110").single().hex())
        }
        assertEquals(EcuAddress('7', "18DA10F1", "18DAF110"), EcuAddress.fromReply('7', "18DAF110"))
        assertEquals(EcuAddress('6', "7E1", "7E9"), EcuAddress.fromReply('6', "7E9"))
    }

    @Test
    fun `ISO 9141 datasheet example - two ECUs, illustrative checksums are reported as wrong`() {
        val lines = listOf("48 6B 10 41 00 BE 3E B8 11 FA", "48 6B 18 41 00 80 10 80 00 C0")
        val engine = HeaderedResponseParser.frame(lines[0], HeaderFormat.ISO_9141)!!
        assertEquals("10", engine.source)
        assertEquals(listOf(0x41, 0x00, 0xBE, 0x3E, 0xB8, 0x11), engine.payload)
        // The datasheet bytes are illustrative: their sums are C9 and 1C, not FA and C0.
        assertFalse(engine.checksumOk!!)
        assertEquals(0xC9, KLineChecksum.of(listOf(0x48, 0x6B, 0x10, 0x41, 0x00, 0xBE, 0x3E, 0xB8, 0x11)))
        assertEquals("18", HeaderedResponseParser.frame(lines[1], HeaderFormat.ISO_9141)!!.source)
        val reply = HeaderedResponseParser.parse(lines, HeaderFormat.ISO_9141)
        assertTrue(reply.byEcu.isEmpty())
        assertEquals(lines, reply.rejected)
        // With the real checksums both ECUs are accepted.
        val fixed = HeaderedResponseParser.parse(listOf("486B104100BE3EB811C9", "48 6B 18 41 00 80 10 80 00 1C"), HeaderFormat.ISO_9141)
        assertEquals(listOf("10", "18"), fixed.byEcu.keys.toList())
        assertEquals("41 00 80 10 80 00", fixed.byEcu.getValue("18").single().hex())
    }

    @Test
    fun `ISO 14230 KWP obd2lib vector - format byte length, target F1, source 10, checksum`() {
        for (line in listOf("86 F1 10 41 00 BE 3E B0 11 85", "86F1104100BE3EB01185")) {
            val f = HeaderedResponseParser.frame(line, HeaderFormat.KWP)!!
            assertEquals("10", f.source)
            assertEquals("F1", f.target)
            assertTrue(f.checksumOk!!)
            assertEquals("41 00 BE 3E B0 11", HeaderedResponseParser.parse(listOf(line), HeaderFormat.KWP).byEcu.getValue("10").single().hex())
        }
        // One flipped data bit breaks the checksum: the frame is rejected, never decoded.
        val bad = HeaderedResponseParser.parse(listOf("86 F1 10 41 00 BE 3E B1 11 85"), HeaderFormat.KWP)
        assertTrue(bad.byEcu.isEmpty())
        assertEquals(1, bad.rejected.size)
    }

    @Test
    fun `synthetic Sorento K-Line - engine 10 and a second ECU 18, then a 58 DTC reply`() {
        val reply = HeaderedResponseParser.parse(listOf("86 F1 10 41 00 BE 3E B0 11 85", "86 F1 18 41 00 80 00 00 00 50"), HeaderFormat.KWP)
        assertEquals(listOf("10", "18"), reply.byEcu.keys.toList())
        val dtcs = HeaderedResponseParser.parse(listOf("88 F1 10 58 02 01 33 E0 03 00 20 1A"), HeaderFormat.KWP)
        assertEquals("58 02 01 33 E0 03 00 20", dtcs.byEcu.getValue("10").single().hex())
        // A format byte whose length does not match the line is rejected.
        assertTrue(HeaderedResponseParser.parse(listOf("87 F1 10 41 00 BE 3E B0 11 86"), HeaderFormat.KWP).byEcu.isEmpty())
        // Length 0 in the format byte: the 4th header byte carries it.
        val long = KLineChecksum.of(listOf(0x80, 0xF1, 0x10, 0x02, 0x7E, 0x00))
        assertEquals("7E 00", HeaderedResponseParser.parse(listOf("80F110027E00%02X".format(long)), HeaderFormat.KWP).byEcu.getValue("10").single().hex())
    }

    @Test
    fun `non-hex lines and unsupported protocols`() {
        assertNull(HeaderedResponseParser.frame("NO DATA", HeaderFormat.CAN_11))
        assertNull(HeaderFormat.of('1'))
        assertNull(HeaderFormat.of('A'))
        assertEquals(HeaderFormat.KWP, HeaderFormat.of('5'))
        assertEquals("0x10", EcuAddress('5', "10", "10").label)
        assertEquals("7D1/7D9", EcuAddress.can11(0x7D1).label)
    }
}

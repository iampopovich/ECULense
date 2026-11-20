package io.github.iampopovich.eculense.core.obd

import org.junit.Assert.assertEquals
import org.junit.Test

class ElmResponseParserTest {
    @Test
    fun `searching line is dropped`() {
        assertEquals(ElmResult.Data(listOf("4100BE3FA813")), ElmResponseParser.parse("SEARCHING...\r4100BE3FA813\r\r"))
    }

    @Test
    fun `spaces are removed`() {
        assertEquals(ElmResult.Data(listOf("410C1AF8")), ElmResponseParser.parse("41 0C 1A F8\r"))
    }

    @Test
    fun `no data`() {
        assertEquals(ElmResult.NoData, ElmResponseParser.parse("NO DATA\r\r"))
    }

    @Test
    fun `adapter errors`() {
        assertEquals(ElmResult.Error("UNABLE TO CONNECT"), ElmResponseParser.parse("UNABLE TO CONNECT\r"))
        assertEquals(ElmResult.Error("?"), ElmResponseParser.parse("?\r"))
        assertEquals(ElmResult.Error("CAN ERROR"), ElmResponseParser.parse("CAN ERROR\r"))
    }

    @Test
    fun `OK of AT command is empty data`() {
        assertEquals(ElmResult.Data(emptyList()), ElmResponseParser.parse("OK\r\r"))
    }

    @Test
    fun `BUS INIT progress and OK are noise`() {
        assertEquals(ElmResult.Data(listOf("4100BE3FA813")), ElmResponseParser.parse("BUS INIT: ...OK\r41 00 BE 3F A8 13\r"))
        assertEquals(ElmResult.Data(listOf("410D20")), ElmResponseParser.parse("BUS INIT: OK\r410D20\r"))
    }

    @Test
    fun `BUS INIT ERROR is its own result`() {
        assertEquals(ElmResult.BusInitFailed, ElmResponseParser.parse("BUS INIT: ...ERROR\r\r"))
        assertEquals(ElmResult.BusInitFailed, ElmResponseParser.parse("BUS INIT: ERROR\r"))
    }

    @Test
    fun `echo of the command and clone plus-lines are dropped`() {
        assertEquals(ElmResult.Data(listOf("410D20")), ElmResponseParser.parse("01 0D\r410D20\r", command = "010D"))
        assertEquals(ElmResult.Data(listOf("410D20")), ElmResponseParser.parse("+CONNECTED\r410D20\r", command = "010D"))
    }
}

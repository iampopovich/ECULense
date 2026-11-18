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
}

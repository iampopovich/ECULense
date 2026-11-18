package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ElmLinkTest {
    @Test
    fun `send returns text before prompt`() = runTest {
        val link = ElmLink(ScriptedTransport(mapOf("ATI" to "ELM327 v1.5")))
        assertEquals("ELM327 v1.5\r\r", link.send("ATI"))
    }

    @Test
    fun `reply split into many chunks is reassembled`() = runTest {
        val link = ElmLink(ScriptedTransport(mapOf("010C" to "410C1AF8"), chunkSize = 3))
        assertEquals("410C1AF8\r\r", link.send("010C"))
    }

    @Test
    fun `two commands in a row get their own replies`() = runTest {
        val link = ElmLink(ScriptedTransport(mapOf("ATE0" to "OK", "ATS0" to "OK", "010D" to "410D20")))
        link.send("ATE0")
        link.send("ATS0")
        assertEquals("410D20\r\r", link.send("010D"))
    }

    @Test(expected = ElmTimeoutException::class)
    fun `silent adapter times out`() = runTest {
        ElmLink(ScriptedTransport(emptyMap(), silent = true)).send("0100", timeoutMs = 100)
    }
}

package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.AdapterInfo
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A0 wave: request/reply sync and clone quirks, on the scripted [CloneScenarios]. */
class CloneScenariosTest {
    private val rpm = StandardPids.byCode(0x0C)!!
    private val speed = StandardPids.byCode(0x0D)!!

    private fun TestScope.session(transport: ObdTransport) = Elm327Session(ElmLink(transport), clock = { testScheduler.currentTime })

    private suspend inline fun <reified T : Throwable> failure(crossinline block: suspend () -> Unit): T {
        val e = runCatching { block() }.exceptionOrNull()
        assertTrue("expected ${T::class.simpleName}, got $e", e is T)
        return e as T
    }

    @Test
    fun `late reply after a timeout does not shift the next command`() = runTest {
        val link = ElmLink(CloneScenarios.lateReplyAfterTimeout(backgroundScope))
        failure<ElmTimeoutException> { link.send("010C") }
        assertEquals("410D20\r\r", link.send("010D"))
        assertEquals("410C1AF8\r\r", link.send("010C"))
    }

    @Test
    fun `live reads recover after a late reply`() = runTest {
        val session = session(CloneScenarios.lateReplyAfterTimeout(backgroundScope))
        failure<ObdException> { session.read(rpm) }
        assertEquals(32.0, session.read(speed)!!.value, 0.0)
        assertEquals(1726.0, session.read(rpm)!!.value, 0.0)
    }

    @Test
    fun `foreign reply that lands in front of the own one is dropped`() = runTest {
        val transport = CloneScenarios.veryLateReply(backgroundScope)
            .on("010D", CloneReply.Late(1_800, CloneReply.Text("410D20")))
        val session = session(transport)
        failure<ObdException> { session.read(rpm) } // 410C arrives at 4 s, while 010D is in flight
        assertEquals(32.0, session.read(speed)!!.value, 0.0)
        assertEquals(1726.0, session.read(rpm)!!.value, 0.0)
    }

    @Test
    fun `only a foreign reply means no value, not a wrong one`() = runTest {
        val session = session(FlakyCloneTransport(backgroundScope).on("010D", CloneReply.Text("410C1AF8")))
        assertNull(session.read(speed))
        assertEquals(32.0, session.read(speed)!!.value, 0.0)
    }

    @Test
    fun `garbage and clone lines before the version are ignored`() = runTest {
        val transport = CloneScenarios.garbageBeforeVersion(backgroundScope)
        val info = session(transport).initialize()
        assertEquals("ELM327 v1.5", info.version)
        assertEquals('6', info.protocolNumber)
    }

    @Test
    fun `banner printed after the ATZ prompt is drained`() = runTest {
        val transport = FlakyCloneTransport(backgroundScope)
            .on("ATZ", CloneReply.Parts(listOf(0L to "ELM327 v1.5\r\r>", 400L to "\rELM327 v1.5\r\r>")))
        val info = session(transport).initialize()
        assertEquals(AdapterInfo("ELM327 v1.5", '6', isCan = true, batteryVoltage = 12.6), info)
    }

    @Test
    fun `echo of the command is not data`() = runTest {
        val transport = FlakyCloneTransport(backgroundScope).on("010D", CloneReply.Text("010D\r410D20"))
        assertEquals(32.0, session(transport).read(speed)!!.value, 0.0)
    }

    @Test
    fun `BUS INIT ERROR is a typed error`() = runTest {
        val e = failure<ObdException> { session(CloneScenarios.busInitError(backgroundScope)).initialize() }
        assertEquals(ObdError.BusInitFailed, e.error)
    }

    @Test
    fun `BUS INIT ERROR on a live read is a typed error too`() = runTest {
        val transport = FlakyCloneTransport(backgroundScope).on("010D", CloneReply.Text("BUS INIT: ERROR"))
        val e = failure<ObdException> { session(transport).read(speed) }
        assertEquals(ObdError.BusInitFailed, e.error)
    }

    @Test
    fun `ATDPN 0 falls back to trying protocols in order`() = runTest {
        val transport = CloneScenarios.atdpnZero(backgroundScope)
        val info = session(transport).initialize()
        assertEquals('3', info.protocolNumber)
        assertFalse(info.isCan)
        val tried = transport.sent.filter { it.startsWith("ATTP") }
        assertEquals(listOf("ATTP6", "ATTP8", "ATTP1", "ATTP7", "ATTP9", "ATTP2", "ATTP3"), tried)
    }

    @Test
    fun `ATDPN A0 with no answering protocol is NoProtocol, not K-Line`() = runTest {
        val replies = FakeCar.replies + ("ATDPN" to "A0")
        val transport = FlakyCloneTransport(backgroundScope, replies).on("0100", CloneReply.Text("SEARCHING...\r4100BE3FA813"))
        repeat(10) { transport.on("0100", CloneReply.Text("UNABLE TO CONNECT")) }
        val e = failure<ObdException> { session(transport).initialize() }
        assertTrue("${e.error}", e.error is ObdError.NoProtocol)
    }

    @Test
    fun `long silence after SEARCHING is not a hang`() = runTest {
        val e = failure<ObdException> { session(CloneScenarios.searchingThenUnable(backgroundScope)).initialize() }
        assertEquals(ObdError.NoProtocol("UNABLE TO CONNECT"), e.error)
    }

    @Test
    fun `STOPPED is retried once`() = runTest {
        val transport = CloneScenarios.stopped(backgroundScope)
        assertEquals(32.0, session(transport).read(speed)!!.value, 0.0)
        assertEquals(listOf("010D", "010D"), transport.sent)
    }

    @Test
    fun `STOPPED twice is an error`() = runTest {
        val transport = FlakyCloneTransport(backgroundScope)
            .on("010D", CloneReply.Text("STOPPED"), CloneReply.Text("STOPPED"))
        val e = failure<ObdException> { session(transport).read(speed) }
        assertEquals(ObdError.CommandFailed("010D", "STOPPED"), e.error)
    }

    @Test
    fun `data without prompt then silence is a hang, detected before the command timeout`() = runTest {
        val link = ElmLink(CloneScenarios.hungMidReply(backgroundScope))
        val start = testScheduler.currentTime
        failure<ElmTimeoutException> { link.send("010D", timeoutMs = 5_000) }
        assertTrue("${testScheduler.currentTime - start}", testScheduler.currentTime - start < 5_000)
        assertEquals("410C1AF8\r\r", link.send("010C"))
    }

    @Test
    fun `stale bytes before a command are dropped`() = runTest {
        val transport = FlakyCloneTransport(backgroundScope).on("ATI", CloneReply.Raw("ELM327 v1.5\r\r>\r>".encodeToByteArray()))
        val link = ElmLink(transport)
        link.send("ATI")
        assertEquals("410D20\r\r", link.send("010D"))
    }
}

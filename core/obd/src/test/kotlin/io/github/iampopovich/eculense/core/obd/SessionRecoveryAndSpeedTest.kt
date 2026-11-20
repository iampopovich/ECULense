package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch 2 of the gap plan at session level: recovery (A2), ATRV states (A4), protocol cache (A5), A6–A8, A10. */
class SessionRecoveryAndSpeedTest {
    private val rpm = StandardPids.byCode(0x0C)!!
    private val speed = StandardPids.byCode(0x0D)!!
    private val coolant = StandardPids.byCode(0x05)!!

    private val car = FakeCar.replies + mapOf(
        "010C1" to "410C1AF8", "010D1" to "410D20", "010C0D" to "410C1AF80D20",
        "ATAT1" to "OK", "ATAT2" to "OK", "ATPC" to "OK", "ATSP6" to "OK", "ATWS" to "ELM327 v1.5",
        "ATSPA6" to "OK", "ATSPA3" to "OK",
    )

    private val saved = mutableListOf<AdapterProfile>()

    private fun TestScope.session(
        transport: ObdTransport,
        settings: AdapterSettings = AdapterSettings(),
        profile: AdapterProfile = AdapterProfile(),
    ) = Elm327Session(
        ElmLink(transport, clock = { testScheduler.currentTime }),
        clock = { testScheduler.currentTime },
        setup = SessionSetup(settings, profile) { saved += it },
    )

    private fun TestScope.flaky(replies: Map<String, String> = car) = FlakyCloneTransport(backgroundScope, replies)

    private suspend fun error(block: suspend () -> Unit): ObdError {
        val e = runCatching { block() }.exceptionOrNull()
        assertTrue("expected ObdException, got $e", e is ObdException)
        return (e as ObdException).error
    }

    // A2: error class → recovery action

    @Test
    fun `error lines map to recovery actions`() {
        assertEquals(RecoveryAction.PROTOCOL_CLOSE, RecoveryAction.forError("UNABLE TO CONNECT"))
        assertEquals(RecoveryAction.PROTOCOL_CLOSE, RecoveryAction.forError("CAN ERROR"))
        assertEquals(RecoveryAction.PROTOCOL_CLOSE, RecoveryAction.forError("BUS BUSY"))
        assertEquals(RecoveryAction.PROTOCOL_CLOSE, RecoveryAction.forError("FB ERROR"))
        assertEquals(RecoveryAction.PROTOCOL_CLOSE, RecoveryAction.forError("BUS INIT: ...ERROR"))
        assertEquals(RecoveryAction.WARM_START, RecoveryAction.forError("BUFFER FULL"))
        assertEquals(RecoveryAction.WARM_START, RecoveryAction.forError("<RX ERROR"))
        assertEquals(RecoveryAction.WARM_START, RecoveryAction.forError("DATA ERROR"))
        assertEquals(RecoveryAction.WARM_START, RecoveryAction.forError("ERR94"))
        assertEquals(RecoveryAction.FULL_REINIT, RecoveryAction.forError("LV RESET"))
        assertEquals(RecoveryAction.NONE, RecoveryAction.forError("?"))
    }

    @Test
    fun `bus error closes the protocol, sets it again and retries`() = runTest {
        val transport = flaky()
        val session = session(transport).apply { initialize() }
        transport.on("010D1", CloneReply.Text("CAN ERROR"))
        assertEquals(32.0, session.read(speed)!!.value, 0.0)
        assertEquals(listOf("010D1", "ATPC", "ATSP6", "010D1"), transport.sent.takeLast(4))
    }

    @Test
    fun `buffer full runs a warm start with the settings and the known protocol`() = runTest {
        val transport = flaky()
        val session = session(transport).apply { initialize() }
        transport.on("010D1", CloneReply.Text("BUFFER FULL"))
        assertEquals(32.0, session.read(speed)!!.value, 0.0)
        assertEquals(listOf("ATWS", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP6", "ATAT1", "010D1"), transport.sent.takeLast(8))
    }

    @Test
    fun `LV RESET runs a full re-init`() = runTest {
        val transport = flaky()
        val session = session(transport).apply { initialize() }
        transport.on("010D1", CloneReply.Text("LV RESET"))
        assertEquals(32.0, session.read(speed)!!.value, 0.0)
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP6", "ATAT1", "010D1"), transport.sent.takeLast(8))
    }

    @Test
    fun `at most three recoveries per minute`() = runTest {
        val transport = flaky()
        val session = session(transport).apply { initialize() }
        repeat(20) { transport.on("010D1", CloneReply.Text("BUS ERROR")) }
        repeat(6) { error { session.read(speed) } }
        assertEquals(3, transport.sent.count { it == "ATPC" })
        delay(61_000)
        error { session.read(speed) }
        assertEquals(4, transport.sent.count { it == "ATPC" })
    }

    @Test
    fun `no recovery before the protocol is known`() = runTest {
        val transport = flaky().on("010D", CloneReply.Text("CAN ERROR"))
        assertEquals(ObdError.CommandFailed("010D", "CAN ERROR"), error { session(transport).read(speed) })
        assertFalse("ATPC" in transport.sent)
    }

    // A4 + C10: what the adapter sees when the car does not answer

    @Test
    fun `silent ECU at battery voltage is ignition off`() = runTest {
        val replies = car + ("0100" to "NO DATA") + ("ATRV" to "12.1V")
        assertEquals(ObdError.IgnitionOff(12.1), error { session(flaky(replies)).initialize() })
    }

    @Test
    fun `UNABLE TO CONNECT at battery voltage is ignition off`() = runTest {
        val replies = car + ("0100" to "UNABLE TO CONNECT") + ("ATRV" to "12.3V")
        assertEquals(ObdError.IgnitionOff(12.3), error { session(flaky(replies)).initialize() })
    }

    @Test
    fun `adapter below 6 V has no power`() = runTest {
        val replies = car + ("0100" to "NO DATA") + ("ATRV" to "4.2V")
        assertEquals(ObdError.AdapterNoPower(4.2), error { session(flaky(replies)).initialize() })
    }

    @Test
    fun `charging voltage with a silent ECU stays EcuSilent`() = runTest {
        val replies = car + ("0100" to "NO DATA")
        assertEquals(ObdError.EcuSilent, error { session(flaky(replies)).initialize() })
    }

    @Test
    fun `adapter that never answers is AdapterNotResponding`() = runTest {
        assertEquals(ObdError.AdapterNotResponding, error { session(ScriptedTransport(car, silent = true)).initialize() })
    }

    // A5: protocol cache

    @Test
    fun `cached protocol is used as the search start and the detected one is saved`() = runTest {
        val transport = flaky()
        session(transport, profile = AdapterProfile(protocol = '6')).initialize()
        assertTrue("ATSPA6" in transport.sent)
        assertFalse("ATSP0" in transport.sent)
        assertEquals('6', saved.last().protocol)
    }

    @Test
    fun `cached protocol that fails falls back to automatic search`() = runTest {
        val transport = flaky().on("0100", CloneReply.Text("UNABLE TO CONNECT"))
        val info = session(transport, profile = AdapterProfile(protocol = '3')).initialize()
        assertEquals('6', info.protocolNumber)
        val order = transport.sent.filter { it == "ATSPA3" || it == "ATSP0" }
        assertEquals(listOf("ATSPA3", "ATSP0"), order)
        assertEquals('6', saved.last().protocol)
    }

    @Test
    fun `manual protocol is set directly and not asked from the adapter`() = runTest {
        val transport = flaky()
        val info = session(transport, settings = AdapterSettings(protocol = '6')).initialize()
        assertEquals('6', info.protocolNumber)
        assertTrue("ATSP6" in transport.sent)
        assertFalse("ATDPN" in transport.sent)
    }

    @Test
    fun `extra init commands are sent after the standard init, invalid ones are not`() = runTest {
        val transport = flaky(car + ("ATST32" to "OK"))
        session(transport, settings = AdapterSettings(extraInit = listOf("ATST32", "ATZ", "04"))).initialize()
        assertTrue("ATST32" in transport.sent)
        assertEquals(1, transport.sent.count { it == "ATZ" })
        assertFalse("04" in transport.sent)
    }

    // A6: identification, unsupported commands are not sent again

    @Test
    fun `commands answered with question mark are remembered and skipped next time`() = runTest {
        session(flaky()).initialize()
        val profile = saved.last()
        assertTrue(profile.unsupported.containsAll(listOf("AT@1", "STI", "VTI")))
        val second = flaky()
        session(second, profile = profile).initialize()
        assertFalse("STI" in second.sent)
        assertFalse("AT@1" in second.sent)
    }

    @Test
    fun `STN adapter is recognized by STI`() = runTest {
        val session = session(flaky(car + ("STI" to "STN1110 v4.0.1") + ("STDI" to "OBDLink SX r4.2")))
        session.initialize()
        assertEquals(AdapterKind.STN, session.capabilities.kind)
        assertEquals("OBDLink SX r4.2", session.capabilities.deviceId)
    }

    @Test
    fun `version strings classify genuine and clone`() {
        assertEquals(AdapterKind.CLONE, AdapterCapabilities.kindOf("ELM327 v1.5", null, null))
        assertEquals(AdapterKind.CLONE, AdapterCapabilities.kindOf("ELM327 v2.1", null, null))
        assertEquals(AdapterKind.GENUINE_ELM, AdapterCapabilities.kindOf("ELM327 v1.4b", null, null))
        assertEquals(AdapterKind.GENUINE_ELM, AdapterCapabilities.kindOf("ELM327 v2.0", null, null))
        assertEquals(AdapterKind.VLINKER, AdapterCapabilities.kindOf("ELM327 v2.2", null, "vLinker FS"))
        assertEquals(AdapterKind.UNKNOWN, AdapterCapabilities.kindOf("", null, null))
    }

    // A7: response-count suffix and adaptive timing

    @Test
    fun `single ECU gets the response-count suffix`() = runTest {
        val transport = flaky()
        val session = session(transport).apply { initialize() }
        assertEquals(1726.0, session.read(rpm)!!.value, 0.0)
        assertEquals("010C1", transport.sent.last())
        assertEquals(true, saved.last().countSuffix)
    }

    @Test
    fun `question mark on the suffix disables it for this adapter`() = runTest {
        val transport = flaky(car - "010C1")
        val session = session(transport).apply { initialize() }
        assertEquals(1726.0, session.read(rpm)!!.value, 0.0)
        assertEquals(listOf("010C1", "010C"), transport.sent.takeLast(2))
        assertEquals(false, saved.last().countSuffix)
        session.read(rpm)
        assertEquals("010C", transport.sent.last())
    }

    @Test
    fun `two ECUs get no suffix`() = runTest {
        val transport = flaky(car + ("0100" to "4100BE3FA813\r4100BE3FA811"))
        val session = session(transport).apply { initialize() }
        session.read(rpm)
        assertEquals("010C", transport.sent.last())
    }

    @Test
    fun `adaptive timing ATAT1 on CAN, ATAT2 on K-Line`() = runTest {
        val can = flaky()
        session(can).initialize()
        assertTrue("ATAT1" in can.sent)
        val kLine = flaky(car + ("ATDPN" to "A3"))
        session(kLine).initialize()
        assertTrue("ATAT2" in kLine.sent)
    }

    // A8: multi-PID requests on CAN

    @Test
    fun `CAN batches PIDs into one request`() = runTest {
        val transport = flaky()
        val session = session(transport).apply { initialize() }
        val results = session.readAll(listOf(rpm, speed))
        assertEquals(listOf(1726.0, 32.0), results.map { it.reading!!.value })
        assertEquals("010C0D", transport.sent.last())
    }

    @Test
    fun `multi-frame multi-PID reply is parsed with PID lengths`() = runTest {
        val transport = flaky(car + ("010C0D05" to "008\r0:410C1AF80D20\r1:057B0000000000"))
        val session = session(transport).apply { initialize() }
        val values = session.readAll(listOf(rpm, speed, coolant)).map { it.reading!!.value }
        assertEquals(listOf(1726.0, 32.0, 83.0), values)
    }

    @Test
    fun `question mark on a multi-PID request falls back to single requests for good`() = runTest {
        val transport = flaky(car - "010C0D")
        val session = session(transport).apply { initialize() }
        assertEquals(listOf(1726.0, 32.0), session.readAll(listOf(rpm, speed)).map { it.reading!!.value })
        session.readAll(listOf(rpm, speed))
        assertEquals(listOf("010C1", "010D1"), transport.sent.takeLast(2))
        assertEquals(1, transport.sent.count { it == "010C0D" })
    }

    @Test
    fun `PID missing from a multi-PID reply is asked alone`() = runTest {
        val transport = flaky(car + ("010C0D" to "410C1AF8"))
        val session = session(transport).apply { initialize() }
        assertEquals(listOf(1726.0, 32.0), session.readAll(listOf(rpm, speed)).map { it.reading!!.value })
        assertEquals(listOf("010C0D", "010D1"), transport.sent.takeLast(2))
    }

    @Test
    fun `K-Line never batches`() = runTest {
        val transport = flaky(car + ("ATDPN" to "A3"))
        val session = session(transport).apply { initialize() }
        session.readAll(listOf(rpm, speed))
        assertFalse("010C0D" in transport.sent)
    }

    // A10: K-Line keepalive on a virtual clock

    @Test
    fun `K-Line idle for more than 4 s gets a keepalive 0100`() = runTest {
        val transport = flaky(car + ("ATDPN" to "A3"))
        val session = session(transport).apply { initialize() }
        assertFalse(session.keepAlive(4_000))
        delay(4_100)
        assertTrue(session.keepAlive(4_000))
        assertEquals("0100", transport.sent.last())
        assertFalse(session.keepAlive(4_000))
    }

    @Test
    fun `KWP uses tester present when the ECU accepts it`() = runTest {
        val transport = flaky(car + ("ATDPN" to "A5") + ("3E" to "7E"))
        val session = session(transport).apply { initialize() }
        delay(4_100)
        assertTrue(session.keepAlive(4_000))
        delay(4_100)
        assertTrue(session.keepAlive(4_000))
        assertEquals(listOf("3E", "3E"), transport.sent.takeLast(2))
    }

    @Test
    fun `CAN never gets a keepalive`() = runTest {
        val transport = flaky()
        val session = session(transport).apply { initialize() }
        val count = transport.sent.size
        delay(60_000)
        assertFalse(session.keepAlive(4_000))
        assertEquals(count, transport.sent.size)
    }

    // A11: init command validation

    @Test
    fun `only safe AT and ST commands pass validation`() {
        assertEquals(listOf("ATST32", "ATCAF0", "STPBR"), InitCommands.valid("at st 32\nATCAF0\n\nSTPBR\n04\nATZ"))
        val problems = InitCommands.problems("ATSH7E0\n04\nATZ\nATH1\nSTSBR 115200")
        assertEquals(
            listOf(
                InitCommandProblem.NotAtCommand("04"),
                InitCommandProblem.Forbidden("ATZ"),
                InitCommandProblem.Forbidden("ATH1"),
                InitCommandProblem.Forbidden("STSBR 115200"),
            ),
            problems,
        )
    }
}

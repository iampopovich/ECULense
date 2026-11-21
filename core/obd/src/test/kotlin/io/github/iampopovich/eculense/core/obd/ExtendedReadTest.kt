package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.SensorReading
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-E: extended reads (planned ELM steps) on the real session over a header-aware fake adapter. The replies are
 * frames of the OBDb Kia-Niro-EV 2020 test cases (CC BY-SA 4.0, see core/demo NOTICE), in ATH1/ATS0 form.
 */
class ExtendedReadTest {
    private val endpoint = AdapterEndpoint("fake", "00:11", "Fake ELM")
    private val bms = EcuTarget(EcuAddress('6', "7E4", "7EC"), emission = false, kind = ModuleKind.BATTERY)
    private val vcu = EcuTarget(EcuAddress('6', "7E2", "7EA"), emission = false, kind = ModuleKind.HYBRID)

    private val niro220101 = listOf(
        "7EC103E620101FFF7E7", "7EC21FF300000000083", "7EC22003B0D75030201", "7EC23020301000001AF", "7EC240DAF3900009200",
        "7EC25074DBF00074F81", "7EC260002BD020002AB", "7EC271E024C608C0D01", "7EC28580000000003E8",
    ).joinToString("\r")
    private val niro2101 = listOf("7EA10186101FFF80000", "7EA2109015924061303", "7EA2200000000B16F34", "7EA2304202005000000").joinToString("\r")

    private fun bmsCommand(fc: Boolean = true) = ExtendedCommand(
        "7E4.7EC.220101",
        bms,
        buildList {
            add(ElmStep.Setting("ATSH7E4"))
            add(ElmStep.Setting("ATCRA7EC"))
            if (fc) {
                add(ElmStep.Setting("ATFCSH7E4"))
                add(ElmStep.Setting("ATFCSD300000"))
                add(ElmStep.Setting("ATFCSM1"))
            }
            add(ElmStep.Setting("ATST32", optional = true))
            add(ElmStep.Request(bms, "220101"))
        },
    )

    private fun vcuCommand() = ExtendedCommand("7E2.7EA.2101", vcu, listOf(ElmStep.Setting("ATSH7E2"), ElmStep.Setting("ATCRA7EA"), ElmStep.Request(vcu, "2101")))

    private fun factory(adapter: ObdTransport) = object : TransportFactory {
        override val kind = "fake"
        override suspend fun discover() = listOf(endpoint)
        override fun create(endpoint: AdapterEndpoint): ObdTransport = adapter
    }

    @Test
    fun `a planned command sets header, filter and flow control, gets the multi-frame reply, and every setting is restored`() = runTest {
        val adapter = MultiEcuFakeAdapter(headered = mapOf("7E4|220101" to niro220101))
        val manager = ObdConnectionManager(listOf(factory(adapter)), backgroundScope)
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connected }
        val outcome = manager.withExclusiveSession { s -> s.withHeaders { it.runExtended(bmsCommand()) } }
        val reply = outcome as ExtendedOutcome.Reply
        assertEquals(62, reply.messages.single().size)
        assertEquals(0x62, reply.messages.single()[0].toInt() and 0xFF)
        val on = adapter.sent.indexOf("ATH1")
        val tail = adapter.sent.drop(on)
        assertEquals(listOf("ATH1", "ATSH7E4", "ATCRA7EC", "ATFCSH7E4", "ATFCSD300000", "ATFCSM1", "ATST32", "220101", "ATFCSM0", "ATST32", "ATH0", "ATSH7DF", "ATCRA"), tail)
        assertFalse(adapter.headers)
        manager.disconnect()
    }

    @Test
    fun `an adapter without ATFCSH marks the command unsupported, the request is never sent`() = runTest {
        val adapter = MultiEcuFakeAdapter(headered = mapOf("7E4|220101" to niro220101), questionMark = setOf("ATFCSH"))
        val manager = ObdConnectionManager(listOf(factory(adapter)), backgroundScope)
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connected }
        val outcome = manager.withExclusiveSession { s -> s.withHeaders { it.runExtended(bmsCommand()) } }
        assertTrue(outcome is ExtendedOutcome.Unsupported)
        assertTrue(adapter.writes.none { it.second == "220101" })
        manager.disconnect()
    }

    @Test
    fun `the safety policy refuses blocked addresses and unlisted sessions before anything is written`() = runTest {
        val adapter = MultiEcuFakeAdapter()
        val manager = ObdConnectionManager(listOf(factory(adapter)), backgroundScope)
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connected }
        val steering = EcuTarget(EcuAddress('6', "7D4", "7DC"), emission = false, kind = ModuleKind.STEERING)
        val blocked = ExtendedCommand("7D4.7DC.220101", steering, listOf(ElmStep.Setting("ATSH7D4"), ElmStep.Request(steering, "220101")))
        val session = ExtendedCommand("7E4.7EC.220105", bms, listOf(ElmStep.Request(bms, "1003", collect = false), ElmStep.Request(bms, "220105")))
        val writeService = ExtendedCommand("7E4.7EC.2E0101", bms, listOf(ElmStep.Request(bms, "2E010100")))
        val outcomes = manager.withExclusiveSession { s ->
            s.withHeaders { ch -> listOf(ch.runExtended(blocked), ch.runExtended(session), ch.runExtended(writeService)) }
        }!!
        assertEquals(listOf("BLOCKED_ADDRESS", "SESSION_NOT_LISTED", "SERVICE_NOT_ALLOWED"), outcomes.map { (it as ExtendedOutcome.Denied).reason })
        assertTrue(adapter.writes.none { it.second.startsWith("22") || it.second.startsWith("10") || it.second.startsWith("2E") })
        assertTrue("ATSH7D4" !in adapter.sent) // refused before the first step, not only before the request
        // Listed sessions pass: 10 03 before and 10 01 after the read.
        val allowed = manager.withExclusiveSession { s -> s.withHeaders(EcuSafetyPolicy(setOf(0x03, 0x01))) { it.runExtended(session) } }
        assertEquals(ExtendedOutcome.NoData, allowed) // the fake has no 7E4 replies: the session switch gets NO DATA
        assertTrue(adapter.writes.any { it.second == "1003" })
        manager.disconnect()
    }

    @Test
    fun `NAK guard pauses with exponential backoff, disables after three failures or a permanent NRC, a reply resets`() {
        var now = 0L
        val guard = CommandGuard(clock = { now })
        guard.record("a", ExtendedOutcome.NoData)
        assertFalse(guard.allowed("a"))
        now = 2_000
        assertTrue(guard.allowed("a"))
        guard.record("a", ExtendedOutcome.Negative(0x22))
        now = 5_999
        assertFalse(guard.allowed("a")) // 4 s pause after the second failure
        now = 6_000
        assertTrue(guard.allowed("a"))
        guard.record("a", ExtendedOutcome.Reply(listOf(byteArrayOf(0x62))))
        guard.record("a", ExtendedOutcome.NoData)
        now += 2_000
        guard.record("a", ExtendedOutcome.NoData)
        assertTrue("a" !in guard.disabled) // the reply reset the count
        now += 4_000
        guard.record("a", ExtendedOutcome.NoData)
        assertTrue("a" in guard.disabled)
        guard.record("b", ExtendedOutcome.Negative(0x31))
        guard.record("c", ExtendedOutcome.Unsupported("?"))
        guard.record("d", ExtendedOutcome.Denied("BLOCKED_ADDRESS"))
        assertEquals(setOf("a", "b", "c", "d"), guard.disabled)
        now += 1_000_000
        assertFalse(guard.allowed("a"))
    }

    @Test
    fun `extended groups are polled between J1979 cycles, one header switch per group, readings decoded`() = runTest {
        val adapter = MultiEcuFakeAdapter(
            headered = mapOf("7E4|220101" to niro220101, "7E4|220105" to "NO DATA", "7E2|2101" to niro2101),
            latencyMs = 20,
        )
        val manager = ObdConnectionManager(listOf(factory(adapter)), backgroundScope, clock = { testScheduler.currentTime })
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connected }
        val readings = mutableListOf<SensorReading>()
        backgroundScope.launch { manager.readings.collect { readings += it } }
        manager.setPolledPids(listOf(StandardPids.byCode(0x0C)!!, StandardPids.byCode(0x0D)!!))
        val soc = ExtendedPollCommand(bmsCommand()) { m, at -> listOf(SensorReading("obdb:SOC", (m.single()[7].toInt() and 0xFF) / 2.0, "%", at)) }
        val missing = ExtendedPollCommand(bmsCommand().copy(id = "7E4.7EC.220105", steps = bmsCommand().steps.dropLast(1) + ElmStep.Request(bms, "220105"))) { _, _ -> emptyList() }
        val vss = ExtendedPollCommand(vcuCommand()) { _, at -> listOf(SensorReading("obdb:VSS", 0.0, "km/h", at)) }
        manager.setExtendedPoll("extended", listOf(ExtendedPollGroup("7E4", 1_000, listOf(soc, missing)), ExtendedPollGroup("7E2", 1_000, listOf(vss))))
        manager.readings.first { it.sensorId == "obdb:SOC" }
        manager.readings.first { it.sensorId == "obdb:VSS" }
        manager.readings.first { it.sensorId == "obdb:SOC" }
        assertEquals(24.0, readings.first { it.sensorId == "obdb:SOC" }.value, 0.001)
        // Order on the wire: J1979 requests sit between the extended groups.
        val reqs = adapter.sent.filter { it == "220101" || it == "2101" || it.startsWith("010C") }
        val firstBms = reqs.indexOf("220101")
        val firstVcu = reqs.indexOf("2101")
        assertTrue(firstBms >= 0 && firstVcu > firstBms)
        assertTrue(reqs.subList(firstBms, firstVcu).any { it.startsWith("010C") })
        // One ATSH7E4 per group scope although the group has two commands.
        val scopeStart = adapter.sent.indexOf("ATH1")
        val scopeEnd = adapter.sent.subList(scopeStart, adapter.sent.size).indexOf("ATH0") + scopeStart
        assertEquals(1, adapter.sent.subList(scopeStart, scopeEnd).count { it == "ATSH7E4" })
        assertTrue(adapter.sent.subList(scopeStart, scopeEnd).contains("220105"))
        manager.setExtendedPoll("extended", emptyList())
        manager.disconnect()
    }
}

package io.github.iampopovich.eculense.core.obdb

import io.github.iampopovich.eculense.core.obd.DenyReason
import io.github.iampopovich.eculense.core.obd.ElmStep
import io.github.iampopovich.eculense.core.obd.ModuleKind
import io.github.iampopovich.eculense.core.obd.PolicyDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-E: OBDb command → ELM steps (synthetic signalsets in the v3 shape; values are our own). */
class ObdbCommandPlannerTest {
    private val signal = """[{"id": "TEST_X", "name": "X", "fmt": {"bix": 0, "len": 8, "unit": "scalar"}}]"""

    private fun parse(commands: String, ecus: String = "[]") = SignalsetParser.parse("""{"ecu": $ecus, "commands": [$commands]}""")

    private fun steps(p: PlannedCommand) = p.command.steps.map {
        when (it) {
            is ElmStep.Setting -> it.command + if (it.optional) "?" else ""
            is ElmStep.Request -> "REQ " + it.command + if (!it.collect) " (session)" else ""
        }
    }

    @Test
    fun `a command with every field gives the exact AT sequence`() {
        val s = parse(
            """{"hdr": "7E4", "rax": "7EC", "eax": "40", "pri": "18", "tst": "F1", "tmo": "64", "fcm1": true, "din": "03", "dout": "01",
               "proto": "15765-4-11bit", "cmd": {"22": "0101"}, "freq": 0.5, "signals": $signal}""",
            """[{"hdr": "7E4", "type": "battery"}]""",
        )
        val plan = ObdbCommandPlanner.plan(s.commands, '6', s.ecus)
        val p = plan.groups.single().commands.single()
        assertEquals(
            listOf(
                "ATSP6", "ATCP18", "ATSH7E4", "ATCEA40", "ATTAF1", "ATST64?", "ATCRA7EC", "ATFCSH7E4", "ATFCSD300000", "ATFCSM1",
                "REQ 1003 (session)", "REQ 220101", "REQ 1001 (session)",
            ),
            steps(p),
        )
        assertEquals("7E4.7EC.220101", p.command.id)
        assertEquals(ModuleKind.BATTERY, p.command.target.kind)
        assertEquals(1_000L, p.periodMs) // freq 0.5 s is raised to the 1 s floor
        // The group's policy allows exactly the listed sessions.
        val policy = plan.groups.single().policy
        assertEquals(PolicyDecision.Allowed, policy.check(p.command.target, "1003"))
        assertEquals(PolicyDecision.Denied(DenyReason.SESSION_NOT_LISTED), policy.check(p.command.target, "1002"))
    }

    @Test
    fun `the default reply address is hdr + 8, without fcm1 no flow control, no proto no ATSP`() {
        val s = parse("""{"hdr": "7E0", "cmd": {"21": "01"}, "freq": 5, "signals": $signal}""")
        val p = ObdbCommandPlanner.plan(s.commands, '6').groups.single().commands.single()
        assertEquals(listOf("ATSH7E0", "ATCRA7E8", "REQ 2101"), steps(p))
        assertEquals(5_000L, p.periodMs)
    }

    @Test
    fun `services other than 01 21 22, unsupported baud rates and other protocol families are refused, init is skipped with a warning`() {
        val s = parse(
            listOf(
                """{"hdr": "7E0", "cmd": {"2E": "0101"}, "signals": $signal}""",
                """{"hdr": "7E0", "proto": "14230", "baud": 12500, "cmd": {"21": "01"}, "signals": $signal}""",
                """{"hdr": "7E0", "proto": "15765-4-29bit", "cmd": {"22": "0101"}, "signals": $signal}""",
                """{"hdr": "10", "rax": "10", "proto": "14230", "baud": 10400, "init": {"lo": 25, "hi": 25}, "cmd": {"21": "01"}, "signals": $signal}""",
                """{"hdr": "7E0", "cmd": {"22": "0101"}, "signals": []}""",
            ).joinToString(","),
        )
        val can = ObdbCommandPlanner.plan(s.commands, '6')
        assertEquals(
            listOf(PlanRefusal.SERVICE_NOT_SUPPORTED, PlanRefusal.PROTOCOL_MISMATCH, PlanRefusal.PROTOCOL_MISMATCH, PlanRefusal.PROTOCOL_MISMATCH, PlanRefusal.NO_SIGNALS),
            can.refused.map { it.reason },
        )
        val kLine = ObdbCommandPlanner.plan(s.commands, '5')
        assertEquals(PlanRefusal.BAUD_NOT_SUPPORTED, kLine.refused.first { it.source.baud == 12500 }.reason)
        val kwp = kLine.groups.single().commands.single()
        assertEquals(listOf("ATSP5", "ATIB10", "ATSH10", "ATSR10", "REQ 2101"), steps(kwp))
        assertEquals(setOf(PlanWarning.INIT_TIMING_IGNORED), kwp.warnings)
    }

    @Test
    fun `29-bit commands carry priority and tester in the header, the reply ID is complete`() {
        val s = parse("""{"hdr": "DA10", "rax": "DAF110", "pri": "18", "proto": "15765-4-29bit", "fcm1": true, "cmd": {"22": "F40D"}, "signals": $signal}""")
        val p = ObdbCommandPlanner.plan(s.commands, '7').groups.single().commands.single()
        assertEquals(listOf("ATSP7", "ATCP18", "ATSHDA10F1", "ATCRA18DAF110", "ATFCSH18DA10F1", "ATFCSD300000", "ATFCSM1", "REQ 22F40D"), steps(p))
        assertEquals("18DAF110", p.command.target.address!!.rx)
    }

    @Test
    fun `commands are grouped by header in signalset order`() {
        val s = parse(
            listOf("7E4" to "0101", "7E2" to "0101", "7E4" to "0105", "7E2" to "0102").joinToString(",") { (h, p) ->
                """{"hdr": "$h", "cmd": {"22": "$p"}, "signals": $signal}"""
            },
        )
        val plan = ObdbCommandPlanner.plan(s.commands, '6')
        assertEquals(listOf("7E4", "7E2"), plan.groups.map { it.header })
        assertEquals(listOf("7E4.220101", "7E4.220105"), plan.groups[0].commands.map { it.command.id })
        assertTrue(plan.groups.all { it.sessions.isEmpty() })
    }

    @Test
    fun `protocol and baud names map to ELM values`() {
        assertEquals('6', ObdbCommandPlanner.protocolOf("15765-4-11bit"))
        assertEquals('7', ObdbCommandPlanner.protocolOf("15765-4-29bit"))
        assertEquals('3', ObdbCommandPlanner.protocolOf("9141-2"))
        assertEquals('5', ObdbCommandPlanner.protocolOf("14230"))
        assertEquals(listOf("10", "96", "48", null, null), listOf(10400, 9600, 4800, 12500, 15625).map(ObdbCommandPlanner::baudOf))
    }
}

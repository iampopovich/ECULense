package io.github.iampopovich.eculense.core.obdb

import io.github.iampopovich.eculense.core.demo.ObdbFixtures
import io.github.iampopovich.eculense.core.obd.ElmStep
import io.github.iampopovich.eculense.core.obd.HeaderFormat
import io.github.iampopovich.eculense.core.obd.HeaderedResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * R-E: OBDb test cases (Kia-Niro-EV 2020, Kia-Sorento 2017; CC BY-SA 4.0, bundled in :core:demo resources/obdb with a
 * NOTICE) as vectors: raw ATH1/ATS0 reply → HeaderedResponseParser → SignalDecoder → OBDb's expected values.
 */
class ObdbTestVectorsTest {
    private data class Check(val repo: String, val command: String, val case: Int, val signal: String, val expected: Double, val actual: Double?)

    private fun run(repo: String, year: Int): List<Check> {
        val signalset = SignalsetParser.parse(ObdbFixtures.signalset(repo))
        val checks = mutableListOf<Check>()
        for (file in ObdbFixtures.testCases(repo)) {
            val parts = file.commandId.substringBefore('|').split('.')
            val hdr = parts[0]
            val rax = parts.getOrNull(1)?.takeIf { parts.size == 3 }
            val cmd = parts.last()
            val command = signalset.forYear(year).single { it.hdr == hdr && it.rax == rax && it.service + it.pid == cmd }
            val plan = ObdbCommandPlanner.plan(listOf(command), '6', signalset.ecus)
            val planned = plan.groups.single().commands.single()
            assertTrue(planned.command.steps.any { it is ElmStep.Request && it.command == cmd })
            file.cases.forEachIndexed { i, case ->
                val reply = HeaderedResponseParser.parse(case.response, HeaderFormat.CAN_11)
                assertTrue("${file.commandId} #$i rejected ${reply.rejected}", reply.rejected.isEmpty())
                val rx = planned.command.target.address!!.rx
                val decoded = SignalDecoder.decodeAll(command, reply.byEcu.getValue(rx)).mapKeys { it.key.id }
                case.expected.forEach { (id, text) ->
                    val expected = text.toDoubleOrNull() ?: return@forEach // map labels: checked separately
                    checks += Check(repo, file.commandId, i, id, expected, decoded[id]?.value)
                }
            }
        }
        return checks
    }

    private fun assertMatches(checks: List<Check>) {
        val bad = checks.filter { c -> c.actual == null || abs(c.actual - c.expected) > 1e-3 + abs(c.expected) * 1e-6 }
        assertTrue("mismatches (${bad.size}/${checks.size}): ${bad.take(15)}", bad.isEmpty())
    }

    @Test
    fun `Kia Niro EV 2020 - BMS 22 0101 and 22 0105 (ISO-TP, flow control) and VCU 21 01 decode to the OBDb values`() {
        val checks = run(ObdbFixtures.NIRO_EV, ObdbFixtures.NIRO_EV_YEAR)
        assertTrue(checks.size > 500)
        assertMatches(checks)
        val first = checks.filter { it.command.startsWith("7E4.7EC.220101") && it.case == 0 }.associate { it.signal to it.actual }
        assertEquals(24.0, first["NIROEV_HVBAT_SOC"]!!, 1e-9)
        assertEquals(344.5, first["NIROEV_HVBAT_VDC"]!!, 1e-9)
        assertEquals(5.9, first["NIROEV_HVBAT_CURR"]!!, 1e-9)
        assertEquals(14.6, first["NIROEV_VPWR"]!!, 1e-9)
    }

    @Test
    fun `Kia Sorento 2017 (non-EV CAN) - engine 21 01, transmission 21 A0 and cluster 22 B002 decode to the OBDb values`() {
        val checks = run(ObdbFixtures.SORENTO, ObdbFixtures.SORENTO_YEAR)
        assertTrue(checks.size > 100)
        assertMatches(checks)
    }

    @Test
    fun `the YAML reader finds every case of a file`() {
        val f = ObdbFixtures.testCases(ObdbFixtures.NIRO_EV).first()
        assertEquals("7E4.7EC.220101|fc=1", f.commandId)
        assertEquals("7E4", f.header)
        assertEquals("220101", f.request)
        assertEquals(34, f.cases.size)
        assertEquals(9, f.cases[0].response.size)
        assertEquals("24", f.cases[0].expected["NIROEV_HVBAT_SOC"])
    }
}

package io.github.iampopovich.eculense.core.background

import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import io.github.iampopovich.eculense.core.analytics.MaintenanceStatus
import io.github.iampopovich.eculense.core.analytics.MaintenanceRule
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertDecisionsTest {
    private fun f(rule: String, sev: Severity) = Finding(rule, sev, rule, "m")

    @Test
    fun `only warning and critical findings that are new since the previous analysis are announced`() {
        val before = listOf(f("ltft", Severity.WARNING), f("maf", Severity.INFO))
        val after = listOf(f("ltft", Severity.WARNING), f("maf", Severity.WARNING), f("volt", Severity.CRITICAL), f("x", Severity.INFO), f("ok", Severity.OK))
        val d = FindingAlerts.decide(carId = 1, before = before, after = after, notified = emptySet())
        assertEquals(listOf("volt", "maf"), d.toNotify.map { it.ruleId })
        assertEquals(setOf("f:1:maf:WARNING", "f:1:volt:CRITICAL", "f:1:ltft:WARNING"), d.notified)
    }

    @Test
    fun `an already announced finding is not repeated even if it flapped in between`() {
        val after = listOf(f("volt", Severity.CRITICAL))
        val d = FindingAlerts.decide(1, before = emptyList(), after = after, notified = setOf("f:1:volt:CRITICAL"))
        assertTrue(d.toNotify.isEmpty())
    }

    @Test
    fun `a finding that went away is forgotten so its return is announced again`() {
        val gone = FindingAlerts.decide(1, before = listOf(f("volt", Severity.CRITICAL)), after = emptyList(), notified = setOf("f:1:volt:CRITICAL", "f:2:x:WARNING"))
        assertEquals(setOf("f:2:x:WARNING"), gone.notified)
        val back = FindingAlerts.decide(1, before = emptyList(), after = listOf(f("volt", Severity.CRITICAL)), notified = gone.notified)
        assertEquals(listOf("volt"), back.toNotify.map { it.ruleId })
    }

    @Test
    fun `severity upgrade is a new finding`() {
        val d = FindingAlerts.decide(1, before = listOf(f("cool", Severity.WARNING)), after = listOf(f("cool", Severity.CRITICAL)), notified = setOf("f:1:cool:WARNING"))
        assertEquals(listOf("cool"), d.toNotify.map { it.ruleId })
    }

    private fun due(id: Long, status: MaintenanceStatus, lastAt: Long? = 100) =
        MaintenanceDue(MaintenanceItem(id = id, carId = 1, title = "w$id", lastDoneAtMs = lastAt, lastDoneKm = 10), status)

    @Test
    fun `maintenance reminder fires once per item state`() {
        val first = MaintenanceAlerts.decide(1, listOf(due(1, MaintenanceStatus.SOON), due(2, MaintenanceStatus.OVERDUE)), emptySet())
        assertEquals(listOf(2L, 1L), first.toNotify.map { it.item.id })
        val again = MaintenanceAlerts.decide(1, listOf(due(1, MaintenanceStatus.SOON), due(2, MaintenanceStatus.OVERDUE)), first.notified)
        assertTrue(again.toNotify.isEmpty())
        // «скоро» became «просрочено»: remind again
        val worse = MaintenanceAlerts.decide(1, listOf(due(1, MaintenanceStatus.OVERDUE), due(2, MaintenanceStatus.OVERDUE)), again.notified)
        assertEquals(listOf(1L), worse.toNotify.map { it.item.id })
        // marked «сделано» → the task left the list, its key is forgotten
        val done = MaintenanceAlerts.decide(1, listOf(due(2, MaintenanceStatus.OVERDUE)), worse.notified)
        assertTrue(done.toNotify.isEmpty())
        assertTrue(done.notified.none { it.startsWith("m:1:1:") })
    }

    @Test
    fun `ok and unknown items never notify`() {
        val d = MaintenanceAlerts.decide(1, listOf(due(1, MaintenanceStatus.OK), due(2, MaintenanceStatus.UNKNOWN)), emptySet())
        assertTrue(d.toNotify.isEmpty())
        assertTrue(MaintenanceRule.SOON_FRACTION > 0)
    }
}

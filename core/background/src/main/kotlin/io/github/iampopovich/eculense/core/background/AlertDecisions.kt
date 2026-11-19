package io.github.iampopovich.eculense.core.background

import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import io.github.iampopovich.eculense.core.analytics.MaintenanceStatus
import io.github.iampopovich.eculense.core.analytics.Severity

/** What to show and which keys to remember as "already shown". */
data class AlertDecision<T>(val toNotify: List<T>, val notified: Set<String>)

/**
 * Findings notifications (3.9 item 3): only WARNING/CRITICAL that were not in the previous analysis and have not been
 * reported yet. Finding identity = car + rule + level (DB row ids change on every recompute).
 * A finding that went away is forgotten: if it comes back, we notify again. A level increase is a new finding.
 */
object FindingAlerts {
    private fun Finding.isProblem() = severity == Severity.WARNING || severity == Severity.CRITICAL
    fun key(carId: Long, f: Finding) = "f:$carId:${f.ruleId}:${f.severity.name}"

    fun decide(carId: Long, before: List<Finding>, after: List<Finding>, notified: Set<String>): AlertDecision<Finding> {
        val prefix = "f:$carId:"
        val beforeKeys = before.filter { it.isProblem() }.map { key(carId, it) }.toSet()
        val current = after.filter { it.isProblem() }
        val currentKeys = current.map { key(carId, it) }.toSet()
        val toNotify = current.filter { key(carId, it) !in beforeKeys && key(carId, it) !in notified }
            .distinctBy { key(carId, it) }.sortedByDescending { it.severity.rank }
        val kept = notified.filterNot { it.startsWith(prefix) && it !in currentKeys }.toSet()
        return AlertDecision(toNotify, kept + currentKeys)
    }
}

/**
 * Daily maintenance check: one reminder per task state (overdue/due soon + when last done).
 * «Сделано» changes the key: the next "due" fires for the new deadline.
 */
object MaintenanceAlerts {
    fun key(carId: Long, d: MaintenanceDue) = "m:$carId:${d.item.id}:${d.status.name}:${d.item.lastDoneAtMs}:${d.item.lastDoneKm}"

    fun decide(carId: Long, due: List<MaintenanceDue>, notified: Set<String>): AlertDecision<MaintenanceDue> {
        val prefix = "m:$carId:"
        val current = due.filter { it.status == MaintenanceStatus.OVERDUE || it.status == MaintenanceStatus.SOON }
        val currentKeys = current.map { key(carId, it) }.toSet()
        val toNotify = current.filter { key(carId, it) !in notified }.sortedByDescending { it.status.rank }
        val kept = notified.filterNot { it.startsWith(prefix) && it !in currentKeys }.toSet()
        return AlertDecision(toNotify, kept + currentKeys)
    }
}

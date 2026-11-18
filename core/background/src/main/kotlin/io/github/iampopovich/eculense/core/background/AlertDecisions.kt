package io.github.iampopovich.eculense.core.background

import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import io.github.iampopovich.eculense.core.analytics.MaintenanceStatus
import io.github.iampopovich.eculense.core.analytics.Severity

/** Что показать и какие ключи запомнить как «уже показанные». */
data class AlertDecision<T>(val toNotify: List<T>, val notified: Set<String>)

/**
 * Уведомления о находках (3.9 п.3): только WARNING/CRITICAL, которых не было в прошлом анализе и о которых ещё не
 * сообщали. Идентичность находки — машина + правило + уровень (id строк в БД меняются при каждом пересчёте).
 * Ушедшая находка забывается: если вернётся — сообщим снова. Повышение уровня — новая находка.
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
 * Ежедневная проверка ТО: напоминание один раз на состояние работы (просрочено/скоро + когда делали).
 * «Сделано» меняет ключ: следующее «пора» придёт уже по новому сроку.
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

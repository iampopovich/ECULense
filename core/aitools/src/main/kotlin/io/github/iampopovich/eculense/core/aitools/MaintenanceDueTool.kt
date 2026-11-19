package io.github.iampopovich.eculense.core.aitools

import ai.koog.serialization.typeToken
import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import io.github.iampopovich.eculense.core.analytics.MaintenanceEnglish
import io.github.iampopovich.eculense.core.analytics.MaintenanceStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * «Что по ТО?»: service items with status (overdue/soon/OK/no data), km and days remaining, current mileage
 * (last manual entry + trips after it — an estimate). Read-only: does not create the service template.
 */
internal class MaintenanceDueTool(ctx: ToolContext) : VehicleTool<NoArgs>(
    typeToken<NoArgs>(), "maintenanceDue",
    "Scheduled maintenance of the active car: each work with status (overdue, soon, ok, unknown), interval, last done, km and days left; " +
        "current odometer (last manual entry plus recorded trips since, estimated). Use for questions about service/TO/oil change.",
    ctx,
) {
    override suspend fun answer(args: NoArgs, carId: Long): JsonObject {
        val repo = ctx.maintenance ?: return Compact.noData("maintenance module is not installed")
        val due = repo.evaluate(carId)
        if (due.isEmpty()) return Compact.noData("maintenance list is empty: the user should open Пит-стоп → Обслуживание to set it up")
        val zone = ctx.zone()
        val estimate = repo.estimate(carId)
        return Compact.fit(due, render = { d -> render(d, zone) }) { works, truncated ->
            buildJsonObject {
                put("today", Dates.date(ctx.clock(), zone))
                if (estimate == null) {
                    put("odometer", "not entered; km-based intervals cannot be checked")
                } else {
                    put("odometer_km", Compact.num(estimate.km, 0))
                    put("odometer_estimated", estimate.estimated)
                    put("odometer_entered_km", estimate.baseKm)
                    put("odometer_entered_date", Dates.date(estimate.baseAtMs, zone))
                }
                put("due_now", due.count { it.status == MaintenanceStatus.OVERDUE || it.status == MaintenanceStatus.SOON })
                put("works", works)
                if (truncated) put("truncated", true)
            }
        }
    }

    private fun render(d: MaintenanceDue, zone: java.time.ZoneId) = buildJsonObject {
        val i = d.item
        put("work", MaintenanceEnglish.title(i))
        put("status", d.status.name.lowercase())
        i.intervalKm?.let { put("interval_km", it) }
        i.intervalMonths?.let { put("interval_months", it) }
        i.lastDoneAtMs?.let { put("last_done_date", Dates.date(it, zone)) }
        i.lastDoneKm?.let { put("last_done_km", it) }
        d.dueKm?.let { put("due_km", it) }
        d.kmLeft?.let { put("km_left", Compact.num(it, 0)) }
        d.dueAtMs?.let { put("due_date", Dates.date(it, zone)) }
        d.daysLeft?.let { put("days_left", it) }
        MaintenanceEnglish.note(i)?.let { put("note", it.take(80)) }
    }

}

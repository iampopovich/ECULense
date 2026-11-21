package io.github.iampopovich.eculense.core.aitools

import ai.koog.serialization.typeToken
import io.github.iampopovich.eculense.core.data.HealthCheckRecord
import io.github.iampopovich.eculense.core.data.ReadinessReason
import io.github.iampopovich.eculense.core.obd.ClearedCounters
import io.github.iampopovich.eculense.core.obd.MonitorStatus
import io.github.iampopovich.eculense.core.obd.Readiness
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** English monitor label for the model: CATALYST → "catalyst". */
private fun monitorName(name: String) = name.lowercase().replace('_', ' ')

private fun yesNo(b: Boolean) = if (b) "yes" else "no"

private fun JsonObjectBuilder.readiness(r: Readiness) {
    put("check_engine_lamp", if (r.milOn) "on" else "off")
    put("stored_emission_dtcs", r.dtcCount)
    val complete = r.monitors.filter { it.status == MonitorStatus.COMPLETE }.map { JsonPrimitive(monitorName(it.monitor.name)) }
    val incomplete = r.monitors.filter { it.status == MonitorStatus.INCOMPLETE }.map { JsonPrimitive(monitorName(it.monitor.name)) }
    put("monitors_complete", JsonArray(complete))
    put("monitors_incomplete", JsonArray(incomplete))
    put("all_monitors_complete", yesNo(r.allComplete))
    put("obd_inspection_ready", yesNo(r.inspectionReady))
}

private fun JsonObjectBuilder.counters(c: ClearedCounters) {
    c.warmUpsSinceClear?.let { put("warm_ups_since_clear", it) }
    c.kmSinceClear?.let { put("km_since_clear", it) }
    c.minutesSinceClear?.let { put("hours_since_clear", Compact.num(it / 60.0, 1)) }
    c.kmWithMil?.let { put("km_with_mil_on", it) }
    c.minutesWithMil?.let { put("hours_with_mil_on", Compact.num(it / 60.0, 1)) }
}

/**
 * «Ready for inspection?», «were codes cleared?»: the latest known OBD readiness (Check Engine lamp, monitors
 * complete/incomplete) and the counters since the last clear (warm-ups, km, time), from the readiness snapshots the
 * app stores around a clear and from the quick health checks. Only real-car data: simulated links store nothing.
 */
internal class ReadinessHistoryTool(ctx: ToolContext) : VehicleTool<LimitArgs>(
    typeToken<LimitArgs>(), NAME,
    "OBD readiness of the active car: the latest known state (Check Engine lamp, stored emission codes, readiness monitors " +
        "complete and incomplete, whether all monitors are complete) and the counters since codes were last cleared " +
        "(warm-ups, km, hours since clear; km/hours with the lamp on), then older snapshots. origin: health_check = a quick check, " +
        "before_clear/after_clear = taken when the user cleared codes in the app (so a clear happened then). Few warm-ups or km " +
        "since clear with no clear in the app means codes were cleared elsewhere (e.g. by a previous owner or a workshop). " +
        "obd_inspection_ready is OBD readiness only, not a legal inspection verdict. Use for questions about readiness, " +
        "inspection/emissions test and whether codes were cleared.",
    ctx,
) {
    override suspend fun answer(args: LimitArgs, carId: Long): JsonObject {
        val limit = args.limit.coerceIn(1, 10)
        val snapshots = ctx.dtc?.readinessHistory(carId, limit).orEmpty().map { s ->
            Entry(s.takenAtMs, if (s.reason == ReadinessReason.AFTER_CLEAR) "after_clear" else if (s.reason == ReadinessReason.BEFORE_CLEAR) "before_clear" else "snapshot", s.readiness, s.counters)
        }
        val checks = ctx.storedHealthChecks(carId, limit).map { Entry(it.takenAtMs, "health_check", it.readiness, it.counters) }
        val all = (snapshots + checks).filter { it.readiness != null || it.counters != null }.sortedByDescending { it.atMs }
        if (all.isEmpty()) {
            return Compact.noData(
                if (ctx.dtc == null && ctx.health == null) "readiness history is not available in this build"
                else "no readiness read yet: run a quick health check (Pit stop) or read codes with the car connected",
            )
        }
        val zone = ctx.zone()
        val latest = all.first()
        val clears = snapshots.filter { it.origin == "after_clear" || it.origin == "before_clear" }.map { Dates.date(it.atMs, zone) }.distinct()
        return Compact.fit(all.drop(1), render = { e ->
            buildJsonObject {
                put("date", Dates.dateTime(e.atMs, zone))
                put("origin", e.origin)
                e.readiness?.let { r ->
                    put("check_engine_lamp", if (r.milOn) "on" else "off")
                    put("incomplete_monitors", r.incompleteCount)
                }
                e.counters?.let { counters(it) }
            }
        }) { older, truncated ->
            buildJsonObject {
                put("today", Dates.date(ctx.clock(), zone))
                // The latest state flat at the top (the on-device digest renders nested objects poorly).
                put("latest_date", Dates.dateTime(latest.atMs, zone))
                put("origin", latest.origin)
                latest.readiness?.let { readiness(it) }
                latest.counters?.let { counters(it) }
                put("cleared_in_app_on", if (clears.isEmpty()) JsonPrimitive("never") else JsonArray(clears.map(::JsonPrimitive)))
                if (older.isNotEmpty()) put("older", older)
                if (truncated) put("truncated", true)
            }
        }
    }

    private class Entry(val atMs: Long, val origin: String, val readiness: Readiness?, val counters: ClearedCounters?)

    companion object {
        const val NAME = "readinessHistory"
    }
}

/** Stored health checks of the car, newest first; a record without an id (simulated link) never counts. */
internal suspend fun ToolContext.storedHealthChecks(carId: Long, limit: Int): List<HealthCheckRecord> =
    health?.observe(carId)?.first().orEmpty().filter { it.id > 0 }.sortedByDescending { it.takenAtMs }.take(limit)

/**
 * «What did the last check show?»: the latest quick health checks with the verdict and every item (status, value,
 * reason). No car name, no VIN, no location.
 */
internal class HealthChecksTool(ctx: ToolContext) : VehicleTool<LimitArgs>(
    typeToken<LimitArgs>(), NAME,
    "Latest quick health checks of the active car, newest first: date, overall verdict and each item " +
        "(stored/pending/permanent codes, check engine, readiness, recent clear, coolant, voltage, fuel trims, O2 sensor, idle) " +
        "with status (ok, attention, fault, not_judged, not_supported), measured value and reason, plus the codes read. " +
        "Use for questions about the last check / quick test result.",
    ctx,
) {
    override suspend fun answer(args: LimitArgs, carId: Long): JsonObject {
        if (ctx.health == null) return Compact.noData("health checks are not available in this build")
        val checks = ctx.storedHealthChecks(carId, args.limit.coerceIn(1, 5))
        if (checks.isEmpty()) return Compact.noData("no quick health check stored yet: the user can run one in Pit stop with the car connected")
        val zone = ctx.zone()
        return Compact.fit(checks, render = { c ->
            buildJsonObject {
                put("date", Dates.dateTime(c.takenAtMs, zone))
                put("verdict", c.result.verdict.name.lowercase())
                // One string per item ("coolant: ok, 91.5"): compact for the cloud model and readable in the digest.
                put("items", JsonArray(c.result.items.map { i ->
                    val parts = listOfNotNull(
                        i.status.name.lowercase().replace('_', ' '),
                        i.value?.let { Compact.num(it, 2).content },
                        i.note.takeIf { it.name != "NONE" }?.name?.lowercase()?.replace('_', ' '),
                    )
                    JsonPrimitive(i.id.name.lowercase().replace('_', ' ') + ": " + parts.joinToString(", "))
                }))
                if (c.dtcs.isNotEmpty()) put("codes", JsonArray(c.dtcs.map { JsonPrimitive("${it.code} ${it.kind.name.lowercase()}") }))
                c.odometerKm?.let { put("odometer_km", it) }
            }
        }) { list, truncated ->
            buildJsonObject {
                put("checks", list)
                if (truncated) put("truncated", true)
            }
        }
    }

    companion object {
        const val NAME = "healthChecks"
    }
}

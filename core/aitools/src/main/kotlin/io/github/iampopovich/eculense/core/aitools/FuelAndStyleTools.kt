package io.github.iampopovich.eculense.core.aitools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.github.iampopovich.eculense.core.analytics.CostSummary
import io.github.iampopovich.eculense.core.analytics.DrivingStyle
import io.github.iampopovich.eculense.core.analytics.DrivingStyleRules
import io.github.iampopovich.eculense.core.analytics.EngineMaps
import io.github.iampopovich.eculense.core.analytics.FuelLog
import io.github.iampopovich.eculense.core.analytics.FuelPeriodSummary
import io.github.iampopovich.eculense.core.analytics.MaintenanceEnglish
import io.github.iampopovich.eculense.core.analytics.RangeEstimate
import io.github.iampopovich.eculense.core.analytics.SensorIds
import io.github.iampopovich.eculense.core.data.FuelInsights
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.put

@Serializable
data class DaysArgs(@property:LLMDescription("days back from today, e.g. 30, 180, 365; 0 = all time") val days: Int = 0)

@Serializable
data class EngineMapArgs(@property:LLMDescription("session id from listSessions; omit for the latest recording with fuel trims") val sessionId: Long? = null)

@Serializable
data class StyleArgs(@property:LLMDescription("how many latest trips with a recording, 1..5") val limit: Int = 5)

private const val DAY_MS = 86_400_000L

/** D1 for the Economist: fill-ups, full-to-full consumption, the app estimate check and the range basis. Read-only. */
internal class FuelLogTool(ctx: ToolContext) : VehicleTool<DaysArgs>(
    typeToken<DaysArgs>(), NAME,
    "Fuel log of the active car: fill-ups and money for the period, real consumption from full-to-full intervals (litres between full " +
        "tanks / km), learned consumption, the check of the app's MAF estimate against filled litres (deviation %, whether the correction " +
        "is applied), tank capacity and range on a full tank.",
    ctx,
) {
    override suspend fun answer(args: DaysArgs, carId: Long): JsonObject {
        val fuel = ctx.fuel ?: return Compact.noData("fuel log is not installed")
        val fills = fuel.fillUps(carId)
        if (fills.isEmpty()) return Compact.noData("no fill-ups logged; consumption is only the app's estimate from trips (see tripStats)")
        val trips = ctx.trips.trips(carId)
        val on = ctx.fuelPrefs?.mafCorrection(carId)?.first() == true
        val i = FuelInsights.of(fills, trips, on)
        val now = ctx.clock()
        val from = if (args.days > 0) now - args.days * DAY_MS else Long.MIN_VALUE
        val p = FuelPeriodSummary.of(fills, i.intervals, from, now)
        val zone = ctx.zone()
        val car = ctx.cars.cars().firstOrNull { it.id == carId }
        return Compact.fit(i.intervals.sortedByDescending { it.toMs }.take(8), render = { f ->
            buildJsonObject {
                put("from", Dates.date(f.fromMs, zone))
                put("to", Dates.date(f.toMs, zone))
                put("km", f.distanceKm)
                put("litres", Compact.num(f.litres, 1))
                put("l_per_100km", Compact.num(f.lPer100Km, 1))
            }
        }) { list, truncated ->
            buildJsonObject {
                put("period_days", if (args.days > 0) args.days.toString() else "all")
                put("fill_ups", p.fillUps)
                put("litres", Compact.num(p.litres, 1))
                put("spend", Compact.num(p.spend, 0))
                car?.currency?.let { put("currency", it) }
                p.lPer100Km?.let { put("consumption_l_per_100km", Compact.num(it, 1)) }
                FuelLog.learnedLPer100(i.intervals)?.let { put("learned_l_per_100km_median_last5", Compact.num(it, 1)) }
                i.calibration?.let { c ->
                    put("app_estimate_l_per_100km", Compact.num(c.estimatedLPer100, 1))
                    put("filled_l_per_100km_same_km", Compact.num(c.realLPer100, 1))
                    put("estimate_deviation_percent", Compact.num(c.deviationPercent, 0))
                    put("recorded_km_compared", Compact.num(c.recordedKm, 0))
                    put("correction_applied", i.correctionOn)
                } ?: put("estimate_check", "not enough recorded trips between full tanks")
                car?.tankVolumeL?.let { tank ->
                    put("tank_l", Compact.num(tank, 0))
                    RangeEstimate.learnedLPer100(i.intervals, i.corrected(trips))?.let { (l, _) ->
                        RangeEstimate.km(100.0, tank, l)?.let { put("range_full_tank_km", Compact.num(it, 0)) }
                    }
                }
                put("intervals", list)
                if (truncated) put("truncated", true)
            }
        }
    }

    companion object {
        const val NAME = "fuelLog"
    }
}

/** D2 for the Economist: cost of ownership by category, per month/day/km, and the latest expenses. Read-only. */
internal class ExpensesTool(ctx: ToolContext) : VehicleTool<DaysArgs>(
    typeToken<DaysArgs>(), NAME,
    "Expenses and cost of ownership of the active car for the period: total incl. fuel from fill-ups, by category (service, repair, parts, " +
        "insurance, tax, parking, wash, other), per month, per day, per km, and the latest expenses with the maintenance work they paid for.",
    ctx,
) {
    override suspend fun answer(args: DaysArgs, carId: Long): JsonObject {
        val repo = ctx.expenses ?: return Compact.noData("expenses module is not installed")
        val expenses = repo.expenses(carId)
        val fills = ctx.fuel?.fillUps(carId).orEmpty()
        if (expenses.isEmpty() && fills.isEmpty()) return Compact.noData("no expenses or fill-ups logged")
        val now = ctx.clock()
        val from = if (args.days > 0) now - args.days * DAY_MS else Long.MIN_VALUE
        val km = ctx.trips.trips(carId).filter { it.startedAtMs in from..now }.sumOf { it.distanceKm }.takeIf { it > 0 }
        val s = CostSummary.of(expenses, fills, from, now, km, ctx.zone())
        val zone = ctx.zone()
        val works = ctx.maintenance?.items(carId).orEmpty().associateBy { it.id }
        val car = ctx.cars.cars().firstOrNull { it.id == carId }
        return Compact.fit(expenses.filter { it.atMs in from..now }.take(15), render = { e ->
            buildJsonObject {
                put("date", Dates.date(e.atMs, zone))
                put("category", e.category.name.lowercase())
                put("amount", Compact.num(e.amount, 0))
                e.maintenanceItemId?.let { id -> works[id]?.let { put("closes_work", MaintenanceEnglish.title(it)) } }
            }
        }) { list, truncated ->
            buildJsonObject {
                put("period_days", if (args.days > 0) args.days.toString() else "all")
                car?.currency?.let { put("currency", it) }
                put("total", Compact.num(s.total, 0))
                put("fuel", Compact.num(s.fuel, 0))
                put("by_category", JsonObject(s.byCategory.entries.associate { (k, v) -> k.name.lowercase() to Compact.num(v, 0) }))
                s.perMonth?.let { put("per_month", Compact.num(it, 0)) }
                s.perDay?.let { put("per_day", Compact.num(it, 1)) }
                s.perKm?.let { put("per_km", Compact.num(it, 2)) }
                km?.let { put("km_from_trips", Compact.num(it, 0)) }
                put("latest", list)
                if (truncated) put("truncated", true)
            }
        }
    }

    companion object {
        const val NAME = "expenses"
    }
}

/** D5 for the Engine specialist: trims binned by rpm and load for one recording, plus the deterministic hypothesis. */
internal class EngineMapTool(ctx: ToolContext) : VehicleTool<EngineMapArgs>(
    typeToken<EngineMapArgs>(), NAME,
    "Engine map of one recording: total fuel trim (STFT+LTFT, %) of warm samples binned by rpm and by engine load, median and sample " +
        "count per bin and bank, idle vs load medians and the app's hypothesis (a pattern to check, not a diagnosis).",
    ctx,
) {
    override suspend fun answer(args: EngineMapArgs, carId: Long): JsonObject {
        val sessions = ctx.sessions ?: return Compact.noData("recordings are not available")
        val candidates = if (args.sessionId != null) listOf(args.sessionId) else ctx.analytics.sessions(carId, limit = 10).map { it.id }
        for (id in candidates) {
            val readings = sessions.readings(id).filter { it.sensorId in EngineMaps.SENSORS }.sortedBy { it.timestampMs }
            val map = EngineMaps.build(readings.map { Triple(it.sensorId, it.value, it.timestampMs) })
            if (map.points.isEmpty()) continue
            fun bins(list: List<io.github.iampopovich.eculense.core.analytics.TrimBin>) = JsonArray(
                list.filter { it.count > 0 }.map { b ->
                    buildJsonObject {
                        put("bank", b.bank)
                        put("range", if (b.to == null) ">=${b.from.toInt()}" else "${b.from.toInt()}-${b.to!!.toInt()}")
                        put("n", b.count)
                        b.median?.let { put("median_trim", Compact.num(it, 1)) }
                    }
                },
            )
            val s = map.summary
            return buildJsonObject {
                put("session_id", id)
                put("warm_samples", map.points.size)
                put("idle_median_trim", s.idleMedian?.let { Compact.num(it, 1) } ?: kotlinx.serialization.json.JsonNull)
                put("load_median_trim", s.loadMedian?.let { Compact.num(it, 1) } ?: kotlinx.serialization.json.JsonNull)
                put("load_defined_by", if (s.loadAxis == io.github.iampopovich.eculense.core.analytics.MapAxis.LOAD) "engine load >= 50 %" else "rpm >= 2500 (no load PID)")
                put("hypothesis", EngineMaps.english(s.pattern))
                put("by_rpm", bins(map.rpmBins))
                if (map.loadBins.any { it.count > 0 }) put("by_load", bins(map.loadBins))
            }.let { full -> if (Compact.bytes(full) <= Compact.MAX_BYTES) full else JsonObject(full - "by_load") }
        }
        return Compact.noData("no recording with STFT, LTFT and rpm on a warm engine")
    }

    companion object {
        const val NAME = "engineMap"
    }
}

/** D6 for the Operations analyst: style score of the latest trips with a recording, or why a trip was not scored. */
internal class DrivingStyleTool(ctx: ToolContext) : VehicleTool<StyleArgs>(
    typeToken<StyleArgs>(), NAME,
    "Driving style of the latest trips with a recording: score 0-100 from harsh accelerations/brakings (speed derivative, no GPS), share " +
        "of high rpm and idling, the biggest deduction; trips with speed sampled slower than once per 2 s are not scored (reason given).",
    ctx,
) {
    override suspend fun answer(args: StyleArgs, carId: Long): JsonObject {
        val sessions = ctx.sessions ?: return Compact.noData("recordings are not available")
        val zone = ctx.zone()
        val trips = ctx.trips.trips(carId).sortedByDescending { it.startedAtMs }
        val rows = mutableListOf<JsonObject>()
        for (t in trips) {
            if (rows.size >= args.limit.coerceIn(1, 5)) break
            val id = ctx.trips.sessionOf(t) ?: continue
            val pts = sessions.readings(id)
                .filter { (it.sensorId == SensorIds.SPEED || it.sensorId == SensorIds.RPM) && it.timestampMs in t.startedAtMs..t.endedAtMs }
                .sortedBy { it.timestampMs }
            val style = DrivingStyleRules.evaluate(pts.map { Triple(it.sensorId, it.value, it.timestampMs) })
            rows += buildJsonObject {
                put("date", Dates.dateTime(t.startedAtMs, zone))
                put("km", Compact.num(t.distanceKm, 1))
                if (style is DrivingStyle.Scored) put("score", style.score)
                put("summary", DrivingStyleRules.english(style))
            }
        }
        if (rows.isEmpty()) return Compact.noData("no trips with a recording")
        return Compact.fit(rows, render = { it }) { list, truncated ->
            buildJsonObject {
                put("trips", list)
                if (truncated) put("truncated", true)
            }
        }
    }

    companion object {
        const val NAME = "drivingStyle"
    }
}

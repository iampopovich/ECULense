package io.github.iampopovich.eculense.core.aitools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.TypeToken
import ai.koog.serialization.typeToken
import android.util.Log
import io.github.iampopovich.eculense.core.analytics.SensorIds
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.data.AnalyticsRepository
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.MaintenanceRepository
import io.github.iampopovich.eculense.core.analytics.FindingEnglish
import io.github.iampopovich.eculense.core.data.StoredFinding
import io.github.iampopovich.eculense.core.data.TripEventRepository
import io.github.iampopovich.eculense.core.data.TripRepository
import io.github.iampopovich.eculense.core.dtcdb.DtcInfo
import io.github.iampopovich.eculense.core.dtcdb.DtcSource
import io.github.iampopovich.eculense.core.model.StatsPeriod
import io.github.iampopovich.eculense.core.model.TripStatistics
import io.github.iampopovich.eculense.core.model.TripStats
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shared tool dependencies. The active car is resolved at call time. log(name, bytes, json) is
 * for on-phone debugging (tag EcuTools); full JSON only with `setprop log.tag.EcuToolsJson VERBOSE`.
 */
internal class ToolContext(
    val analytics: AnalyticsRepository,
    val cars: CarRepository,
    val trips: TripRepository,
    val clock: () -> Long = System::currentTimeMillis,
    val zone: () -> ZoneId = ZoneId::systemDefault,
    val log: (tool: String, bytes: Int, json: String) -> Unit = ::logcat,
    /** Make-aware DTC descriptions (OBDex/factory database); without packs, the built-in CSV. */
    val describeDtc: suspend (codes: Collection<String>, make: String?) -> Map<String, DtcInfo> = { _, _ -> emptyMap() },
    /** Maintenance and mileage (3.9); null means the maintenance module is not wired in. */
    val maintenance: MaintenanceRepository? = null,
    /** B10: stalls and user marks inside recordings; null means the events store is not wired in. */
    val events: TripEventRepository? = null,
)

/** B10: events of one recording for the model (English, no location): type, local time, seconds from the start. */
internal suspend fun ToolContext.eventsJson(sessionId: Long, startedAtMs: Long): JsonArray? {
    val list = events?.forSession(sessionId).orEmpty()
    if (list.isEmpty()) return null
    val zone = zone()
    return JsonArray(
        list.take(MAX_EVENTS).map { e ->
            buildJsonObject {
                put("type", if (e.type == io.github.iampopovich.eculense.core.analytics.TripEventType.STALL) "engine_stall" else "user_mark")
                put("at", Dates.dateTime(e.atMs, zone))
                put("offset_s", ((e.atMs - startedAtMs) / 1000).coerceAtLeast(0))
            }
        },
    )
}

private const val MAX_EVENTS = 10

private fun logcat(tool: String, bytes: Int, json: String) {
    runCatching {
        Log.d(TOOLS_TAG, "tool=$tool bytes=$bytes")
        if (Log.isLoggable(JSON_TAG, Log.VERBOSE)) Log.v(JSON_TAG, "$tool $json")
    }
}

internal const val TOOLS_TAG = "EcuTools"
private const val JSON_TAG = "EcuToolsJson"

/** Tool responses: compact JSON (≤ ~2 KB), rounded numbers, no VIN or coordinates. */
internal object Compact {
    const val MAX_BYTES = 2_000

    fun bytes(e: JsonElement) = e.toString().toByteArray().size

    fun num(v: Double, decimals: Int = 1): JsonPrimitive =
        if (decimals == 0) JsonPrimitive(Math.round(v)) else JsonPrimitive(BigDecimal(v).setScale(decimals, RoundingMode.HALF_UP).toDouble())

    /** How many list items fit into the budget together with the rest of the response; truncated = true if cut. */
    fun <T> fit(items: List<T>, render: (T) -> JsonElement, maxBytes: Int = MAX_BYTES, build: (JsonArray, Boolean) -> JsonObject): JsonObject {
        val rendered = items.map(render)
        var n = rendered.size
        while (n > 0 && bytes(build(JsonArray(rendered.take(n)), n < rendered.size)) > maxBytes) n--
        return build(JsonArray(rendered.take(n)), n < rendered.size)
    }

    fun noData(reason: String) = buildJsonObject { put("no_data", reason) }
}

internal object Dates {
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val date = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    fun dateTime(ms: Long, zone: ZoneId): String = dateTime.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun date(ms: Long, zone: ZoneId): String = date.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun startOf(day: String?, zone: ZoneId): Long? = day?.let { runCatching { LocalDate.parse(it).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull() }
    fun endOf(day: String?, zone: ZoneId): Long? = startOf(day, zone)?.let { it + 86_400_000L - 1 }
}

/** Short English sensor labels: cheaper in tokens than Russian ones (UTF-8, 2 bytes per letter). */
internal val SENSOR_NAMES = mapOf(
    "01.04" to "engine load", SensorIds.COOLANT to "coolant temp", SensorIds.STFT1 to "STFT bank1", SensorIds.LTFT1 to "LTFT bank1",
    SensorIds.STFT2 to "STFT bank2", SensorIds.LTFT2 to "LTFT bank2", SensorIds.RPM to "rpm", SensorIds.SPEED to "speed",
    "01.0E" to "timing advance", SensorIds.IAT to "intake air temp", SensorIds.MAF to "MAF", "01.11" to "throttle",
    SensorIds.MIL_DISTANCE to "distance with MIL on", SensorIds.MODULE_VOLTAGE to "ECU voltage", SensorIds.ELM_VOLTAGE to "OBD socket voltage (adapter)",
    "cs.20" to "O2 B1S1 voltage", "cs.21" to "O2 B1S1 trim", "cs.22" to "O2 B1S2 voltage", "cs.23" to "O2 B1S2 trim",
    "cs.28" to "O2 B2S1 voltage", "cs.29" to "O2 B2S1 trim", "cs.30" to "O2 B2S2 voltage", "cs.31" to "O2 B2S2 trim",
)

internal fun period(value: String?): StatsPeriod = when (value?.lowercase()) {
    "week" -> StatsPeriod.WEEK
    "month" -> StatsPeriod.MONTH
    "year" -> StatsPeriod.YEAR
    else -> StatsPeriod.ALL
}

internal fun periodFrom(p: StatsPeriod, now: Long): Long = p.days?.let { now - it * 86_400_000L } ?: 0L

/**
 * Period start: [days] (arbitrary, «half a year» = 180) takes priority over the named [period].
 * Returns (start, period label for the response).
 */
internal fun periodStart(period: String?, days: Int?, now: Long): Pair<Long, String> {
    val d = days?.coerceIn(1, 3_650)
    if (d != null) return now - d * 86_400_000L to "$d days"
    val p = period(period)
    return periodFrom(p, now) to p.name.lowercase()
}

/**
 * Base tool: read-only over the active car; the response is compact JSON, its size is logged.
 * Typed [SimpleTool]s with @Serializable arguments rather than @Tool methods of [ai.koog.agents.core.tools.reflect.ToolSet]:
 * no kotlin-reflect at call time, and arguments are validated by kotlinx.serialization.
 */
internal abstract class VehicleTool<A>(
    argsType: TypeToken,
    name: String,
    description: String,
    protected val ctx: ToolContext,
) : SimpleTool<A>(argsType, name, description) {
    protected abstract suspend fun answer(args: A, carId: Long): JsonObject

    final override suspend fun execute(args: A): String {
        val json = json(args).toString()
        ctx.log(name, json.toByteArray().size, json)
        return json
    }

    /** Unserialized response, for the on-phone model's text digest ([DigestText]). */
    internal suspend fun json(args: A): JsonObject {
        val carId = ctx.cars.activeCarId()
        return if (carId == null) Compact.noData("no car profile") else answer(args, carId)
    }
}

@Serializable
class NoArgs

@Serializable
data class PeriodArgs(
    @property:LLMDescription("week, month, year or all") val period: String = "all",
    @property:LLMDescription("optional number of days back from today (e.g. 180 for half a year); overrides period") val days: Int? = null,
)

@Serializable
data class ListSessionsArgs(
    @property:LLMDescription("max sessions, 1..20, newest first") val limit: Int = 10,
    @property:LLMDescription("optional start date YYYY-MM-DD") val from: String? = null,
    @property:LLMDescription("optional end date YYYY-MM-DD") val to: String? = null,
)

@Serializable
data class SessionArgs(@property:LLMDescription("session id from listSessions") val sessionId: Long)

@Serializable
data class SensorArgs(
    @property:LLMDescription("sensor id, e.g. 01.05 coolant, 01.0C rpm, 01.10 MAF, 01.07/01.09 long-term fuel trim, 01.06/01.08 short-term trim, elm.voltage or 01.42 voltage")
    val sensorId: String,
    @property:LLMDescription("week, month, year or all") val period: String = "all",
    @property:LLMDescription("only readings after the engine reached 80 C in that session") val warmOnly: Boolean = false,
    @property:LLMDescription("optional number of days back from today; overrides period") val days: Int? = null,
)

@Serializable
data class LimitArgs(@property:LLMDescription("max items") val limit: Int = 10)

internal class CarProfileTool(ctx: ToolContext) : VehicleTool<NoArgs>(
    typeToken<NoArgs>(), "carProfile", "Car profile of the active car: brand, model, year, engine, fuel, gearbox, odometer, service intervals. No VIN.", ctx,
) {
    override suspend fun answer(args: NoArgs, carId: Long): JsonObject {
        val car = ctx.cars.cars().firstOrNull { it.id == carId } ?: return Compact.noData("no car profile")
        // VIN, name and external id are not exposed: the model doesn't need them.
        return buildJsonObject {
            car.brand?.let { put("brand", it) }
            car.model?.let { put("model", it) }
            car.generation?.let { put("generation", it) }
            car.year?.let { put("year", it) }
            car.engineCode?.let { put("engine_code", it) }
            car.engineVolumeL?.let { put("engine_l", Compact.num(it)) }
            car.cylinders?.let { put("cylinders", it) }
            car.fuelType?.let { put("fuel", it.name.lowercase()) }
            car.gearbox?.let { put("gearbox", it.name.lowercase()) }
            car.drive?.let { put("drive", it.name.lowercase()) }
            car.curbWeightKg?.let { put("curb_weight_kg", it) }
            car.tankVolumeL?.let { put("tank_l", Compact.num(it, 0)) }
            car.odometerKm?.let { put("odometer_km", it) }
            car.odometerAtMs?.let { put("odometer_date", Dates.date(it, ctx.zone())) }
            val s = car.service
            if (listOf(s.oilKm, s.sparkPlugsKm, s.airFilterKm, s.fuelFilterKm).any { it != null }) {
                put(
                    "service_interval_km",
                    buildJsonObject {
                        s.oilKm?.let { put("oil", it) }
                        s.sparkPlugsKm?.let { put("spark_plugs", it) }
                        s.airFilterKm?.let { put("air_filter", it) }
                        s.fuelFilterKm?.let { put("fuel_filter", it) }
                    },
                )
            }
            val missing = buildList {
                if (car.engineVolumeL == null) add("engine_l")
                if (car.model == null) add("model")
            }
            if (missing.isNotEmpty()) put("missing", JsonArray(missing.map { JsonPrimitive(it) }))
        }
    }
}

internal class TripStatsTool(ctx: ToolContext) : VehicleTool<PeriodArgs>(
    typeToken<PeriodArgs>(), "tripStats",
    "Trip totals for a period (trips imported from Car Scanner and trips computed from own recordings, fuel of those may be a MAF estimate): count, km, fuel, average l/100km, driving/standing hours, fuel cost, the last trip and recent months.",
    ctx,
) {
    override suspend fun answer(args: PeriodArgs, carId: Long): JsonObject {
        val all = ctx.trips.observeTrips(carId).first()
        if (all.isEmpty()) return Compact.noData("no trips; trips come from Car Scanner import or from recordings with speed data")
        val now = ctx.clock()
        val zone = ctx.zone()
        val (from, label) = periodStart(args.period, args.days, now)
        val inPeriod = all.filter { it.startedAtMs in from..now }
        val currency = ctx.cars.cars().firstOrNull { it.id == carId }?.currency
        fun JsonObjectBuilder.stats(s: TripStats, count: Boolean = true) {
            if (count) put("trips", s.trips)
            put("km", Compact.num(s.distanceKm))
            put("fuel_l", Compact.num(s.fuelL))
            s.avgLPer100Km?.let { put("l_per_100km", Compact.num(it)) }
            put("driving_h", Compact.num(s.drivingMs / 3_600_000.0))
            put("standing_h", Compact.num(s.standingMs / 3_600_000.0))
            if (s.fuelCost > 0) put("fuel_cost", Compact.num(s.fuelCost, 0))
        }
        return buildJsonObject {
            put("period", label)
            stats(TripStats.of(inPeriod))
            currency?.let { put("currency", it) }
            all.maxByOrNull { it.startedAtMs }?.let { t ->
                put(
                    "last_trip",
                    buildJsonObject {
                        put("start", Dates.dateTime(t.startedAtMs, zone))
                        put("min", (t.endedAtMs - t.startedAtMs) / 60_000)
                        stats(TripStats.of(listOf(t)), count = false)
                    },
                )
            }
            put(
                "recent_months",
                JsonArray(
                    TripStatistics.byMonth(all, zone).take(3).map { (m, s) ->
                        buildJsonObject {
                            put("month", m.toString())
                            put("trips", s.trips)
                            put("km", Compact.num(s.distanceKm, 0))
                            s.avgLPer100Km?.let { put("l_per_100km", Compact.num(it)) }
                        }
                    },
                ),
            )
        }
    }

}

internal class ListSessionsTool(ctx: ToolContext) : VehicleTool<ListSessionsArgs>(
    typeToken<ListSessionsArgs>(), "listSessions",
    "Recorded OBD sessions (live recordings and Car Scanner imports), newest first: id, local start time, duration in minutes, source, number of readings, events (engine stalls and driver marks, when any). Use the id with sessionStats.",
    ctx,
) {
    override suspend fun answer(args: ListSessionsArgs, carId: Long): JsonObject {
        val zone = ctx.zone()
        val list = ctx.analytics.sessions(
            carId,
            Dates.startOf(args.from, zone) ?: 0L,
            Dates.endOf(args.to, zone) ?: Long.MAX_VALUE,
            args.limit.coerceIn(1, 20),
        )
        if (list.isEmpty()) return Compact.noData("no sessions in this range")
        val eventCounts = list.associate { it.id to (ctx.events?.forSession(it.id)?.size ?: 0) }
        return Compact.fit(
            list,
            { s ->
                buildJsonObject {
                    put("id", s.id)
                    put("start", Dates.dateTime(s.startedAtMs, zone))
                    s.endedAtMs?.let { put("min", (it - s.startedAtMs) / 60_000) }
                    put("source", if (s.source.name == "LIVE") "live" else "carscanner")
                    put("readings", s.readings)
                    eventCounts[s.id]?.takeIf { it > 0 }?.let { put("events", it) }
                }
            },
        ) { items, truncated ->
            buildJsonObject {
                put("sessions", items)
                if (truncated) put("truncated", true)
            }
        }
    }
}

internal class SessionStatsTool(ctx: ToolContext) : VehicleTool<SessionArgs>(
    typeToken<SessionArgs>(), "sessionStats",
    "Statistics of one session per sensor (n, min, avg, max, p95; values in the given unit) plus rule findings for that session and its events (engine_stall = rpm dropped to 0 while the ECU kept answering; user_mark = the driver tapped 'mark event'; look at the data +-2 min around them).",
    ctx,
) {
    override suspend fun answer(args: SessionArgs, carId: Long): JsonObject {
        val report = ctx.analytics.sessionReport(carId, args.sessionId) ?: return Compact.noData("no such session")
        val zone = ctx.zone()
        val findings = report.findings.map { f -> findingJson(f.ruleId, f.severity, FindingEnglish.title(f), FindingEnglish.evidence(f)) }
        val events = ctx.eventsJson(report.session.id, report.session.startedAtMs)
        return Compact.fit(
            report.sensors.sortedByDescending { it.sensorId in SENSOR_NAMES },
            { s ->
                buildJsonObject {
                    put("id", s.sensorId)
                    SENSOR_NAMES[s.sensorId]?.let { put("name", it) }
                    put("unit", s.unit)
                    put("n", s.count)
                    put("min", Compact.num(s.min))
                    put("avg", Compact.num(s.mean))
                    put("max", Compact.num(s.max))
                    s.p95?.let { put("p95", Compact.num(it)) }
                }
            },
        ) { items, truncated ->
            buildJsonObject {
                put("id", report.session.id)
                put("start", Dates.dateTime(report.session.startedAtMs, zone))
                report.session.endedAtMs?.let { put("min", (it - report.session.startedAtMs) / 60_000) }
                put("sensors", items)
                if (truncated) put("truncated", true)
                put("findings", JsonArray(findings))
                events?.let { put("events", it) }
            }
        }
    }
}

internal class SensorSummaryTool(ctx: ToolContext) : VehicleTool<SensorArgs>(
    typeToken<SensorArgs>(), "sensorSummary",
    "Summary of one sensor across all sessions of a period: n, sessions, min, median, avg, p95, max. warmOnly=true keeps readings after the engine reached 80 C.",
    ctx,
) {
    override suspend fun answer(args: SensorArgs, carId: Long): JsonObject {
        val (from, label) = periodStart(args.period, args.days, ctx.clock())
        val s = ctx.analytics.sensorSummary(carId, args.sensorId, from, Long.MAX_VALUE, args.warmOnly)
            ?: return Compact.noData("no readings of ${args.sensorId}${if (args.warmOnly) " on a warm engine" else ""} in period $label")
        return buildJsonObject {
            put("sensor", s.sensorId)
            SENSOR_NAMES[s.sensorId]?.let { put("name", it) }
            put("unit", s.unit)
            put("period", label)
            put("warm_only", args.warmOnly)
            put("n", s.count)
            put("sessions", s.sessions)
            put("min", Compact.num(s.min))
            s.median?.let { put("median", Compact.num(it)) }
            put("avg", Compact.num(s.mean))
            s.p95?.let { put("p95", Compact.num(it)) }
            put("max", Compact.num(s.max))
        }
    }
}

internal fun findingJson(ruleId: String, severity: Severity, title: String, evidence: Map<String, String>) = buildJsonObject {
    put("rule", ruleId)
    put("severity", severity.name.lowercase())
    put("title", title)
    if (evidence.isNotEmpty()) put("evidence", JsonObject(evidence.mapValues { JsonPrimitive(it.value) }))
}

internal class FindingsTool(ctx: ToolContext) : VehicleTool<LimitArgs>(
    typeToken<LimitArgs>(), "findings",
    "Findings of the app's deterministic analysis over all recordings and DTC history (severity critical/warning/info with evidence numbers), plus checks without data and checks that are ok.",
    ctx,
) {
    override suspend fun answer(args: LimitArgs, carId: Long): JsonObject {
        var stored: List<StoredFinding> = ctx.analytics.findings(carId)
        // No findings yet (analysis never opened): compute them; the rules are deterministic, no network needed.
        if (stored.isEmpty()) {
            ctx.analytics.recompute(carId)
            stored = ctx.analytics.findings(carId)
        }
        if (stored.isEmpty()) return Compact.noData("no recordings to analyse")
        val zone = ctx.zone()
        val problems = stored.filter { it.finding.severity.rank >= Severity.INFO.rank }.sortedByDescending { it.finding.severity.rank }.take(args.limit.coerceIn(1, 20))
        return Compact.fit(
            problems,
            { s -> findingJson(s.finding.ruleId, s.finding.severity, FindingEnglish.title(s.finding), FindingEnglish.evidence(s.finding)) },
        ) { items, truncated ->
            buildJsonObject {
                put("computed_at", Dates.dateTime(stored.maxOf { it.createdAtMs }, zone))
                put("problems", items)
                if (truncated) put("truncated", true)
                put("no_data", JsonArray(stored.filter { it.finding.severity == Severity.NO_DATA }.map { JsonPrimitive("${it.finding.ruleId}: ${FindingEnglish.message(it.finding).take(90)}") }))
                put("ok", JsonArray(stored.filter { it.finding.severity == Severity.OK }.map { JsonPrimitive(it.finding.ruleId) }))
            }
        }
    }
}

internal class DtcHistoryTool(ctx: ToolContext) : VehicleTool<LimitArgs>(
    typeToken<LimitArgs>(), "dtcHistory",
    "History of diagnostic trouble codes: live reads in the app and DTC events imported from Car Scanner. Per code: kind (stored/pending), " +
        "number of events, first and last date, title, and (when known) the most likely causes and symptoms from the OBDex database. " +
        "freeze_frame (when the ECU saved one) = conditions at the moment the code was stored: sensor id -> value with unit " +
        "(01.0C rpm, 01.0D speed, 01.05 coolant, 01.04 load, 01.06/01.07 fuel trims). " +
        "source=oem_unknown means a manufacturer-specific code whose meaning for this make is unknown — do not guess it from other makes.",
    ctx,
) {
    override suspend fun answer(args: LimitArgs, carId: Long): JsonObject {
        val events = ctx.analytics.dtcEvents(carId)
        if (events.isEmpty()) return Compact.noData("no DTC events recorded")
        val zone = ctx.zone()
        val codes = events.groupBy { it.code }.map { (code, list) -> code to list.sortedBy { it.atMs } }
            .sortedByDescending { (_, l) -> l.last().atMs }.take(args.limit.coerceIn(1, 30))
        val make = ctx.cars.cars().firstOrNull { it.id == carId }?.brand
        val info = runCatching { ctx.describeDtc(codes.map { it.first }, make) }.getOrDefault(emptyMap())
        // First with causes and symptoms; if it doesn't fit in 2 KB, drop symptoms, then causes, then trim the list.
        fun answer(detail: Int) = Compact.fit(
            codes,
            { (code, list) ->
                buildJsonObject {
                    put("code", code)
                    put("kind", list.last().kind.name.lowercase())
                    put("events", list.map { it.atMs }.distinct().size)
                    put("first", Dates.date(list.first().atMs, zone))
                    put("last", Dates.date(list.last().atMs, zone))
                    // B3: the latest freeze frame of this code, compact (at most 8 values).
                    if (detail >= 1) {
                        list.lastOrNull { it.freezeFrame != null }?.freezeFrame?.let { ff ->
                            put("freeze_frame", buildJsonObject {
                                ff.readings.take(8).forEach { r ->
                                    put(r.sensorId, BigDecimal(r.value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + (if (r.unit.isEmpty()) "" else " ${r.unit}"))
                                }
                            })
                        }
                    }
                    info[code]?.let { i ->
                        i.title?.let { put("title", short(it, 70)) }
                        put("source", i.source.json)
                        if (detail >= 1 && i.causes.isNotEmpty()) put("causes", JsonArray(i.causes.take(2).map { JsonPrimitive(short(it, 60)) }))
                        if (detail >= 2 && i.symptoms.isNotEmpty()) put("symptoms", JsonArray(i.symptoms.take(1).map { JsonPrimitive(short(it, 60)) }))
                    }
                }
            },
        ) { items, truncated ->
            buildJsonObject {
                put("codes", items)
                if (truncated) put("truncated", true)
                put("total_events", events.size)
            }
        }
        return (2 downTo 1).asSequence().map(::answer).firstOrNull { !it.containsKey("truncated") } ?: answer(0)
    }

    private fun short(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    private val DtcSource.json: String
        get() = when (this) {
            DtcSource.OBDEX, DtcSource.BUILTIN, DtcSource.UNKNOWN -> "generic"
            DtcSource.OEM -> "oem"
            DtcSource.OEM_UNKNOWN -> "oem_unknown"
        }
}

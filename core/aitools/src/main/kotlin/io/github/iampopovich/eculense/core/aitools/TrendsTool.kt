package io.github.iampopovich.eculense.core.aitools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.github.iampopovich.eculense.core.analytics.BucketTrend
import io.github.iampopovich.eculense.core.analytics.SensorIds
import io.github.iampopovich.eculense.core.analytics.TrendBucket
import io.github.iampopovich.eculense.core.analytics.Trends
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class TrendsArgs(
    @property:LLMDescription("days back from today, 7..730; 180 = half a year, 365 = a year") val days: Int = 180,
    @property:LLMDescription("month or week; default: week when days <= 45, otherwise month") val bucket: String? = null,
)

/**
 * Trends over an arbitrary period by months/weeks: trips and fuel consumption, sensors (medians from session digests), DTCs
 * and the "change" from the first to the last bucket with enough data. [Trends] (:core:analytics) computes; this is JSON only.
 */
internal class TrendsTool(ctx: ToolContext) : VehicleTool<TrendsArgs>(
    typeToken<TrendsArgs>(), NAME,
    "Trends of the active car over the last N days, by month (by week when days <= 45), newest first: trips, km, fuel, l/100km, " +
        "idle (standing) share, average trip length; per bucket medians of coolant temp (and max), voltage while running " +
        "(median and minimum), warm fuel trims, warm idle rpm with the number of readings; DTC events and codes first seen. " +
        "change = first vs last bucket with enough data; few_data = metrics without enough data. Empty buckets are marked.",
    ctx,
) {
    override suspend fun answer(args: TrendsArgs, carId: Long): JsonObject {
        val days = args.days.coerceIn(MIN_DAYS, MAX_DAYS)
        val now = ctx.clock()
        val from = now - days * DAY_MS
        val trips = ctx.trips.observeTrips(carId).first()
        val digests = ctx.analytics.sessionDigests(carId, from, now)
        val dtc = ctx.analytics.dtcEvents(carId)
        val bucket = when (args.bucket?.lowercase()) {
            "week" -> TrendBucket.WEEK
            "month" -> TrendBucket.MONTH
            else -> null
        }
        val report = Trends.build(days, bucket, now, ctx.zone(), trips, digests, dtc)
        if (report.buckets.all { it.empty }) {
            return Compact.noData("no trips, recordings or DTC events in the last $days days")
        }
        val totals = report.totals
        return Compact.fit(report.buckets.reversed(), ::bucketJson, MAX_BYTES) { items, truncated ->
            buildJsonObject {
                put("days", days)
                put("bucket", report.bucket.name.lowercase())
                put(
                    "totals",
                    buildJsonObject {
                        put("trips", totals.trips)
                        put("km", Compact.num(totals.distanceKm))
                        put("fuel_l", Compact.num(totals.fuelL))
                        totals.avgLPer100Km?.let { put("l_per_100km", Compact.num(it)) }
                    },
                )
                put(
                    "change",
                    JsonArray(
                        report.changes.map { c ->
                            buildJsonObject {
                                put("metric", c.metric)
                                put("from", c.fromLabel)
                                put("to", c.toLabel)
                                put("was", metricNum(c.metric, c.from))
                                put("now", metricNum(c.metric, c.to))
                                put("delta", metricNum(c.metric, c.delta))
                            }
                        },
                    ),
                )
                if (report.insufficient.isNotEmpty()) put("few_data", JsonArray(report.insufficient.map { JsonPrimitive(it) }))
                put("order", "newest first")
                put("buckets", items)
                if (truncated) put("truncated", true)
            }
        }
    }

    /** Parked share in percent (integers), everything else with one decimal. */
    private fun metricNum(metric: String, v: Double): JsonPrimitive =
        if (metric == "idle_share") Compact.num(v * 100, 0) else Compact.num(v)

    private fun bucketJson(b: BucketTrend): JsonObject = buildJsonObject {
        put("label", b.label)
        if (b.empty) {
            put("empty", true)
            return@buildJsonObject
        }
        if (b.trips.trips > 0) {
            put("trips", b.trips.trips)
            put("km", Compact.num(b.trips.distanceKm))
            put("fuel_l", Compact.num(b.trips.fuelL))
            b.trips.avgLPer100Km?.let { put("l_per_100km", Compact.num(it)) }
            b.idleShare?.let { put("idle_pct", Compact.num(it * 100, 0)) }
            b.avgTripKm?.let { put("avg_trip_km", Compact.num(it)) }
        }
        if (b.sessions > 0) put("sessions", b.sessions)
        if (b.coolantN > 0) {
            put("coolant_n", b.coolantN)
            b.coolantMedian?.let { put("coolant_median", Compact.num(it)) }
            b.coolantMax?.let { put("coolant_max", Compact.num(it)) }
        }
        if (b.voltageN > 0) {
            put("voltage_n", b.voltageN)
            b.voltageMedian?.let { put("voltage_median", Compact.num(it)) }
            b.voltageMinRunning?.let { put("voltage_min_running", Compact.num(it)) }
        }
        TRIM_KEYS.forEach { (id, key) -> b.trimMedians[id]?.let { put(key, Compact.num(it)) } }
        b.trimN[SensorIds.LTFT1]?.let { put("trim_n", it) }
        if (b.idleRpmN > 0) b.idleRpmMedian?.let { put("idle_rpm_median", Compact.num(it, 0)) }
        if (b.dtcEvents > 0) put("dtc_events", b.dtcEvents)
        if (b.newCodes.isNotEmpty()) {
            put("new_codes", JsonArray(b.newCodes.take(MAX_CODES).map { JsonPrimitive(it) }))
            if (b.newCodes.size > MAX_CODES) put("new_codes_more", b.newCodes.size - MAX_CODES)
        }
    }

    companion object {
        const val NAME = "trends"
        const val MIN_DAYS = 7
        const val MAX_DAYS = 730
        private const val MAX_CODES = 6
        /** Up to 7 buckets (half a year by months) + change: a bigger budget than the other tools. */
        const val MAX_BYTES = 4_000
        private const val DAY_MS = 86_400_000L
        val TRIM_KEYS = listOf(
            SensorIds.LTFT1 to "ltft1_median", SensorIds.LTFT2 to "ltft2_median",
            SensorIds.STFT1 to "stft1_median", SensorIds.STFT2 to "stft2_median",
        )
    }
}

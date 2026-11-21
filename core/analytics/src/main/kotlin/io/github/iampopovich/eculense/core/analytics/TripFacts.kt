package io.github.iampopovich.eculense.core.analytics

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.floor

/** Where the outside temperature of a trip came from. */
enum class AmbientSource { AMBIENT_PID, INTAKE_AIR }

/**
 * Per-trip facts derived once from its recording and cached ([styleScore] null = not scored, e.g. sparse samples;
 * [ambientC] null = no usable temperature). Cached so the Stats screen never re-reads recordings.
 */
data class TripFacts(val styleScore: Int?, val ambientC: Double?, val ambientSource: AmbientSource?) {
    companion object {
        val NONE = TripFacts(null, null, null)
    }
}

/**
 * Thresholds of the ambient-temperature estimate. Sources:
 * - PID 0x46 «ambient air temperature» (SAE J1979 / ISO 15031-5) is the outside air sensor; used when recorded.
 * - Fallback PID 0x0F «intake air temperature» only while moving faster than [IAT_MIN_SPEED_KMH] on a warm engine
 *   ([Thresholds.WARM_COOLANT_C]): at standstill and low speed the intake air is heated by the engine bay (heat soak),
 *   so IAT reads well above ambient; with ram airflow at speed it settles near the outside temperature. It stays an
 *   approximation (a few °C above ambient depending on the intake layout).
 * How to calibrate on a real car: on a trip with PID 46 (or a weather report for the time and place) compare IAT at
 * > 30 km/h with the ambient value; if the offset is large, raise [IAT_MIN_SPEED_KMH] or subtract the offset.
 */
object AmbientThresholds {
    /** Statistics threshold for using IAT as an outside-air estimate, not an engine norm. */
    const val IAT_MIN_SPEED_KMH = 30.0
    /** Speed sample older than this does not describe the IAT sample (K-Line cycles are ~1.7 s). */
    const val MAX_AGE_MS = 4_000L
    /** At least this many temperature samples per trip (statistics threshold, not an engine norm). */
    const val MIN_SAMPLES = 5
}

object TripFactsRules {
    const val AMBIENT = "01.46"

    /** Sensors the facts need from a recording. */
    val SENSORS = listOf(SensorIds.SPEED, SensorIds.RPM, SensorIds.COOLANT, SensorIds.IAT, AMBIENT)

    /** [points] = (sensorId, value, timestampMs) of the trip span in time order. */
    fun compute(points: List<Triple<String, Double, Long>>, harshMs2: Double = StyleThresholds.HARSH_ACCEL_MS2): TripFacts {
        val style = DrivingStyleRules.evaluate(points.filter { it.first == SensorIds.SPEED || it.first == SensorIds.RPM }, harshMs2)
        val (ambient, source) = ambient(points)
        return TripFacts((style as? DrivingStyle.Scored)?.score, ambient, source)
    }

    /** Median of PID 46, else median IAT at speed > 30 km/h on a warm engine; null when too few samples. */
    fun ambient(points: List<Triple<String, Double, Long>>): Pair<Double?, AmbientSource?> {
        val direct = points.filter { it.first == AMBIENT }.map { it.second }
        if (direct.size >= AmbientThresholds.MIN_SAMPLES) return median(direct) to AmbientSource.AMBIENT_PID
        var speed: Pair<Double, Long>? = null
        var warm = false
        val iat = ArrayList<Double>()
        for ((id, v, t) in points) {
            when (id) {
                SensorIds.SPEED -> speed = v to t
                SensorIds.COOLANT -> if (v >= Thresholds.WARM_COOLANT_C) warm = true
                SensorIds.IAT -> {
                    val s = speed
                    if (warm && s != null && t - s.second <= AmbientThresholds.MAX_AGE_MS && s.first > AmbientThresholds.IAT_MIN_SPEED_KMH) iat += v
                }
            }
        }
        return if (iat.size >= AmbientThresholds.MIN_SAMPLES) median(iat) to AmbientSource.INTAKE_AIR else null to null
    }

    internal fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

/** Grouping of the style trend: [TrendBucket] from Trends.kt (MONTH, WEEK). */

/** Median style score of the scored trips that started in the week/month beginning on [start]. */
data class StyleTrendPoint(val start: LocalDate, val medianScore: Int, val trips: Int)

/** Consumption of the trips whose ambient temperature falls into [fromC], [fromC] + 5 °C. */
data class TemperatureBucket(val fromC: Int, val medianLPer100Km: Double, val trips: Int)

/**
 * R2.8 Stats trends from cached [TripFacts]. Display thresholds below are presentation choices (how much data a row
 * needs before it is worth showing), not physical constants.
 */
object StatsTrends {
    /** Style trend card needs at least this many periods with a scored trip (display threshold, not an engine norm). */
    const val MIN_STYLE_PERIODS = 2

    /** Width of a temperature bucket (display threshold, not an engine norm). */
    const val BUCKET_C = 5

    /**
     * A temperature bucket is shown with at least this many trips; the card needs [MIN_TEMPERATURE_BUCKETS] of them.
     * Display threshold, not an engine norm: a median of fewer trips is dominated by one trip's route.
     */
    const val MIN_TRIPS_PER_BUCKET = 3
    const val MIN_TEMPERATURE_BUCKETS = 2

    /**
     * Trips shorter than this are left out of the temperature comparison: their L/100 km is dominated by the warm-up
     * and by the distance resolution. Display threshold, not an engine norm (DOE fueleconomy.gov «Fuel Economy in Cold Weather»: the cold
     * penalty is largest on short trips, 3–4 miles, so trips of a few km still belong in the comparison).
     */
    const val MIN_TRIP_KM = 2.0

    fun styleTrend(scored: List<Pair<Long, Int>>, bucket: TrendBucket, zone: ZoneId): List<StyleTrendPoint> {
        if (scored.isEmpty()) return emptyList()
        val groups = scored.groupBy { (startMs, _) -> periodStart(startMs, bucket, zone) }
        val points = groups.entries.sortedBy { it.key }.map { (start, list) ->
            StyleTrendPoint(start, TripFactsRules.median(list.map { it.second.toDouble() }).let { Math.round(it).toInt() }, list.size)
        }
        return if (points.size >= MIN_STYLE_PERIODS) points else emptyList()
    }

    /** [trips] = (L/100 km, ambient °C) of trips with known fuel; buckets of 5 °C with enough trips, cold first. */
    fun consumptionByTemperature(trips: List<Pair<Double, Double>>): List<TemperatureBucket> {
        val buckets = trips.filter { it.first > 0 && it.first.isFinite() }
            .groupBy { (_, c) -> (floor(c / BUCKET_C) * BUCKET_C).toInt() }
            .filterValues { it.size >= MIN_TRIPS_PER_BUCKET }
            .entries.sortedBy { it.key }
            .map { (from, list) -> TemperatureBucket(from, TripFactsRules.median(list.map { it.first }), list.size) }
        return if (buckets.size >= MIN_TEMPERATURE_BUCKETS) buckets else emptyList()
    }

    fun periodStart(startMs: Long, bucket: TrendBucket, zone: ZoneId): LocalDate {
        val d = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        return when (bucket) {
            TrendBucket.WEEK -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            TrendBucket.MONTH -> d.withDayOfMonth(1)
        }
    }
}

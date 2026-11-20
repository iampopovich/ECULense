package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.model.Expense
import io.github.iampopovich.eculense.core.model.ExpenseCategory
import io.github.iampopovich.eculense.core.model.FuelFillUp
import io.github.iampopovich.eculense.core.model.FuelMethod
import io.github.iampopovich.eculense.core.model.Trip
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.max

/** One full-to-full interval: everything filled after the [fromKm] full tank up to and including the [toKm] full tank. */
data class FullToFull(
    val fromMs: Long,
    val toMs: Long,
    val fromKm: Long,
    val toKm: Long,
    val litres: Double,
    /** Fill-ups summed into [litres] (partial ones plus the closing full one). */
    val fillUps: Int,
) {
    val distanceKm: Long get() = toKm - fromKm
    val lPer100Km: Double get() = litres / distanceKm * 100.0
}

/**
 * D1 consumption from the fuel log, «full to full»: a full tank opens an interval, partial fills add their litres, the next
 * full tank closes it (its litres count: they refill what was burned since the opening tank). A fill-up flagged
 * [FuelFillUp.missedPrevious] breaks the chain (an unlogged fill-up would make the litres too low); a full one marked so
 * opens a new interval. A fill-up without an odometer can't close an interval: if full it breaks the chain, if partial
 * its litres still count. Intervals with zero/negative distance (typo in the odometer) are skipped.
 */
object FuelLog {
    fun fullToFull(fills: List<FuelFillUp>): List<FullToFull> {
        val out = mutableListOf<FullToFull>()
        var anchor: FuelFillUp? = null
        var litres = 0.0
        var count = 0
        for (f in fills.sortedWith(compareBy<FuelFillUp> { it.atMs }.thenBy { it.odometerKm ?: Long.MAX_VALUE }.thenBy { it.id })) {
            if (f.missedPrevious) {
                anchor = null
                litres = 0.0
                count = 0
            }
            val a = anchor
            if (!f.fullTank) {
                if (a != null) {
                    litres += f.litres
                    count++
                }
                continue
            }
            val odo = f.odometerKm
            if (odo == null) {
                anchor = null
                litres = 0.0
                count = 0
                continue
            }
            val aKm = a?.odometerKm
            if (a != null && aKm != null && odo > aKm) {
                out += FullToFull(a.atMs, f.atMs, aKm, odo, litres + f.litres, count + 1)
            }
            anchor = f
            litres = 0.0
            count = 0
        }
        return out
    }

    /** Median l/100 km of the last [n] intervals: the «learned» consumption for range; null when there is none. */
    fun learnedLPer100(intervals: List<FullToFull>, n: Int = LEARN_LAST): Double? =
        median(intervals.sortedBy { it.toMs }.takeLast(n).map { it.lPer100Km })

    const val LEARN_LAST = 5

    internal fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}

/** Totals of the fuel log for a period: litres and money from fill-ups, consumption from intervals closed in it. */
data class FuelPeriodSummary(
    val fillUps: Int,
    val litres: Double,
    val spend: Double,
    val intervals: Int,
    val intervalKm: Long,
    val intervalLitres: Double,
) {
    val lPer100Km: Double? get() = if (intervalKm > 0) intervalLitres / intervalKm * 100.0 else null

    companion object {
        fun of(fills: List<FuelFillUp>, intervals: List<FullToFull>, fromMs: Long, toMs: Long): FuelPeriodSummary {
            val inPeriod = fills.filter { it.atMs in fromMs..toMs }
            val closed = intervals.filter { it.toMs in fromMs..toMs }
            return FuelPeriodSummary(
                fillUps = inPeriod.size,
                litres = inPeriod.sumOf { it.litres },
                spend = inPeriod.sumOf { it.total ?: ((it.pricePerL ?: 0.0) * it.litres) },
                intervals = closed.size,
                intervalKm = closed.sumOf { it.distanceKm },
                intervalLitres = closed.sumOf { it.litres },
            )
        }
    }
}

/**
 * D1 calibration: the app's own fuel estimate (MAF or 01.5E integral of recorded trips) against the litres actually filled
 * for the same intervals. Only intervals whose recorded trips cover at least [MIN_COVERAGE] of the driven distance count,
 * and the comparison needs [MIN_KM] of recorded distance overall.
 * [ratio] = estimated / real consumption (0.88 = «the estimate is 12 % low»); [factor] = 1 / ratio corrects the estimate.
 */
data class FuelCalibration(
    val intervals: Int,
    val recordedKm: Double,
    val estimatedLPer100: Double,
    val realLPer100: Double,
) {
    val ratio: Double get() = estimatedLPer100 / realLPer100
    val factor: Double get() = realLPer100 / estimatedLPer100
    /** Signed percent: negative = the estimate is lower than the real consumption. */
    val deviationPercent: Double get() = (ratio - 1.0) * 100.0

    companion object {
        const val MIN_COVERAGE = 0.5
        const val MIN_KM = 50.0
        /** A factor outside this range means a broken input (wrong odometer, tank not really full), not a calibration. */
        val SANE_FACTOR = 0.5..2.0

        fun compute(intervals: List<FullToFull>, trips: List<Trip>): FuelCalibration? {
            val estimated = trips.filter { it.fuelMethod == FuelMethod.MAF_ESTIMATE || it.fuelMethod == FuelMethod.OBD_FUEL_RATE }
            var km = 0.0
            var estL = 0.0
            var realWeighted = 0.0
            var used = 0
            for (i in intervals) {
                val inside = estimated.filter { it.startedAtMs >= i.fromMs && it.endedAtMs <= i.toMs }
                val d = inside.sumOf { it.distanceKm }
                if (i.distanceKm <= 0 || d < MIN_COVERAGE * i.distanceKm) continue
                km += d
                estL += inside.sumOf { it.fuelL }
                // The real consumption of this interval applied to the recorded part of it.
                realWeighted += i.lPer100Km * d / 100.0
                used++
            }
            if (used == 0 || km < MIN_KM || estL <= 0 || realWeighted <= 0) return null
            val c = FuelCalibration(used, km, estL / km * 100.0, realWeighted / km * 100.0)
            return c.takeIf { it.factor in SANE_FACTOR }
        }
    }
}

/** D3: «~230 km» left from the fuel level (PID 2F), the tank capacity and the learned consumption. */
object RangeEstimate {
    /** null when any input is missing or not plausible. */
    fun km(fuelLevelPercent: Double?, tankL: Double?, lPer100: Double?): Double? {
        if (fuelLevelPercent == null || tankL == null || lPer100 == null) return null
        if (fuelLevelPercent !in 0.0..100.0 || tankL <= 0 || lPer100 !in 2.0..40.0) return null
        return fuelLevelPercent / 100.0 * tankL / lPer100 * 100.0
    }

    /**
     * Learned consumption: the median of the last full-to-full intervals; without a fuel log the median of the last trips
     * with a fuel estimate (at least [MIN_TRIP_KM] each, so a cold short hop doesn't dominate).
     */
    fun learnedLPer100(intervals: List<FullToFull>, trips: List<Trip>, n: Int = FuelLog.LEARN_LAST): Pair<Double, Source>? {
        FuelLog.learnedLPer100(intervals, n)?.let { return it to Source.FUEL_LOG }
        val recent = trips.filter { it.fuelKnown && it.fuelMethod != null && it.distanceKm >= MIN_TRIP_KM && it.fuelL > 0 }
            .sortedBy { it.startedAtMs }.takeLast(n)
        return FuelLog.median(recent.map { it.fuelL / it.distanceKm * 100.0 })?.let { it to Source.TRIPS }
    }

    const val MIN_TRIP_KM = 5.0

    enum class Source { FUEL_LOG, TRIPS }
}

/** D2 totals: by category (fuel from fill-ups), per day / month / km over [fromMs]..[toMs]. */
data class CostSummary(
    val total: Double,
    val fuel: Double,
    val byCategory: Map<ExpenseCategory, Double>,
    val days: Long,
    val distanceKm: Double?,
    /** Newest first. */
    val byMonth: List<Pair<YearMonth, Double>>,
) {
    val perDay: Double? get() = if (days > 0) total / days else null
    val perMonth: Double? get() = if (days > 0) total / days * 30.4375 else null
    val perKm: Double? get() = distanceKm?.takeIf { it > 0 }?.let { total / it }

    companion object {
        private const val DAY_MS = 86_400_000L

        /**
         * [distanceKm]: driven in the period — from the odometer readings when known, else from trips (the caller decides).
         * The period starts at the first entry when [fromMs] is open (all time).
         */
        fun of(expenses: List<Expense>, fills: List<FuelFillUp>, fromMs: Long, toMs: Long, distanceKm: Double?, zone: ZoneId = ZoneId.systemDefault()): CostSummary {
            val e = expenses.filter { it.atMs in fromMs..toMs }
            val f = fills.filter { it.atMs in fromMs..toMs }
            val fuelOf = { x: FuelFillUp -> x.total ?: ((x.pricePerL ?: 0.0) * x.litres) }
            val fuel = f.sumOf(fuelOf)
            val byCategory = e.groupBy { it.category }.mapValues { (_, l) -> l.sumOf { it.amount } }
            val first = (e.map { it.atMs } + f.map { it.atMs }).minOrNull()
            val start = if (fromMs == Long.MIN_VALUE || fromMs <= 0) first ?: toMs else fromMs
            val days = if (first == null) 0 else max(1L, (toMs - start + DAY_MS - 1) / DAY_MS)
            val month = { ms: Long -> YearMonth.from(Instant.ofEpochMilli(ms).atZone(zone)) }
            val months = (e.map { month(it.atMs) to it.amount } + f.map { month(it.atMs) to fuelOf(it) })
                .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.toList().sortedByDescending { it.first }
            return CostSummary(fuel + byCategory.values.sum(), fuel, byCategory, days, distanceKm, months)
        }
    }
}

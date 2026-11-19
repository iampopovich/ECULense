package io.github.iampopovich.eculense.core.model

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Trip totals for a period. Average consumption = all fuel / all distance (Car Scanner computes it the same way);
 * distance of trips without a fuel estimate ([Trip.fuelKnown] = false) is excluded from the denominator.
 */
data class TripStats(
    val trips: Int,
    val distanceKm: Double,
    val fuelL: Double,
    val drivingMs: Long,
    val standingMs: Long,
    val fuelCost: Double,
    /** Distance of trips with known fuel. */
    val fuelDistanceKm: Double = distanceKm,
) {
    /** null when there is no distance: division by zero must not turn into «бесконечный расход». */
    val avgLPer100Km: Double? get() = if (fuelDistanceKm > 0.0) fuelL / fuelDistanceKm * 100.0 else null

    companion object {
        val EMPTY = TripStats(0, 0.0, 0.0, 0, 0, 0.0)

        fun of(trips: Iterable<Trip>): TripStats = trips.fold(EMPTY) { acc, t ->
            TripStats(
                trips = acc.trips + 1,
                distanceKm = acc.distanceKm + t.distanceKm,
                fuelL = acc.fuelL + t.fuelL,
                drivingMs = acc.drivingMs + t.drivingMs,
                standingMs = acc.standingMs + t.standingMs,
                fuelCost = acc.fuelCost + t.fuelL * (t.fuelPricePerL ?: 0.0),
                fuelDistanceKm = acc.fuelDistanceKm + if (t.fuelKnown) t.distanceKm else 0.0,
            )
        }
    }
}

enum class StatsPeriod(val days: Long?) { ALL(null), YEAR(365), MONTH(30), WEEK(7) }

object TripStatistics {
    private const val DAY_MS = 86_400_000L

    /** Trips that started within the last period.days days before now (ALL means all). */
    fun inPeriod(trips: List<Trip>, period: StatsPeriod, nowMs: Long): List<Trip> {
        val from = period.days?.let { nowMs - it * DAY_MS } ?: Long.MIN_VALUE
        return trips.filter { it.startedAtMs in from..nowMs }
    }

    fun forPeriod(trips: List<Trip>, period: StatsPeriod, nowMs: Long): TripStats = TripStats.of(inPeriod(trips, period, nowMs))

    /** Monthly in local time, newest to oldest. */
    fun byMonth(trips: List<Trip>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<YearMonth, TripStats>> =
        trips.groupBy { YearMonth.from(Instant.ofEpochMilli(it.startedAtMs).atZone(zone)) }
            .map { (month, list) -> month to TripStats.of(list) }
            .sortedByDescending { it.first }
}

package io.github.iampopovich.eculense.core.model

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Итоги поездок за период. Средний расход = всё топливо / весь путь (так же считает Car Scanner). */
data class TripStats(
    val trips: Int,
    val distanceKm: Double,
    val fuelL: Double,
    val drivingMs: Long,
    val standingMs: Long,
    val fuelCost: Double,
) {
    /** null, когда пути нет: деление на ноль не должно превращаться в «бесконечный расход». */
    val avgLPer100Km: Double? get() = if (distanceKm > 0.0) fuelL / distanceKm * 100.0 else null

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
            )
        }
    }
}

enum class StatsPeriod(val days: Long?) { ALL(null), YEAR(365), MONTH(30), WEEK(7) }

object TripStatistics {
    private const val DAY_MS = 86_400_000L

    /** Поездки, начавшиеся в последние period.days суток до now (ALL — все). */
    fun inPeriod(trips: List<Trip>, period: StatsPeriod, nowMs: Long): List<Trip> {
        val from = period.days?.let { nowMs - it * DAY_MS } ?: Long.MIN_VALUE
        return trips.filter { it.startedAtMs in from..nowMs }
    }

    fun forPeriod(trips: List<Trip>, period: StatsPeriod, nowMs: Long): TripStats = TripStats.of(inPeriod(trips, period, nowMs))

    /** Помесячно по местному времени, от новых к старым. */
    fun byMonth(trips: List<Trip>, zone: ZoneId = ZoneId.systemDefault()): List<Pair<YearMonth, TripStats>> =
        trips.groupBy { YearMonth.from(Instant.ofEpochMilli(it.startedAtMs).atZone(zone)) }
            .map { (month, list) -> month to TripStats.of(list) }
            .sortedByDescending { it.first }
}

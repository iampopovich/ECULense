package io.github.iampopovich.eculense.feature.stats

import io.github.iampopovich.eculense.core.model.StatsPeriod
import io.github.iampopovich.eculense.core.model.UnitsSettings
import java.util.Locale

internal object StatsFormat {
    /** Thousands separator is always a non-breaking space (ICU yields either U+00A0 or the narrow U+202F). */
    fun number(v: Double, decimals: Int = 1): String = String.format(Locale.getDefault(), "%,.${decimals}f", v).replace('\u202F', '\u00A0')

    fun distance(km: Double, units: UnitsSettings): String = units.distance(km).let { (v, u) -> "${number(v)} $u" }

    /** Under a liter — with hundredths: otherwise a short trip shows «0,0 л». */
    fun volume(l: Double, units: UnitsSettings): String = units.volume(l).let { (v, u) -> "${number(v, if (v < 1.0) 2 else 1)} $u" }

    fun consumption(lPer100: Double?, units: UnitsSettings): String =
        lPer100?.let { units.consumption(it).let { (v, u) -> "${number(v)} $u" } } ?: "—"

    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes >= 60) "${minutes / 60} ч ${"%02d".format(minutes % 60)} мин" else "$minutes мин"
    }

    fun money(v: Double, currency: String?): String = "${number(v, 0)} ${currency ?: ""}".trim()

    val StatsPeriod.label: String
        get() = when (this) {
            StatsPeriod.WEEK -> "Неделя"
            StatsPeriod.MONTH -> "Месяц"
            StatsPeriod.YEAR -> "Год"
            StatsPeriod.ALL -> "Всё время"
        }

    /** Order in the toggle — from the shortest period to the longest. */
    val periods = listOf(StatsPeriod.WEEK, StatsPeriod.MONTH, StatsPeriod.YEAR, StatsPeriod.ALL)

    const val NO_TRIPS_AT_ALL = "Поездок пока нет"
    const val EMPTY_HINT = "Запишите поездку на Панели или импортируйте бэкап Car Scanner"

    /** Placeholder title: no trips at all → «пока нет», otherwise for the selected period. */
    fun emptyTitle(period: StatsPeriod, totalTrips: Int): String =
        if (totalTrips == 0) NO_TRIPS_AT_ALL
        else "Нет поездок за " + when (period) {
            StatsPeriod.WEEK -> "неделю"
            StatsPeriod.MONTH -> "месяц"
            StatsPeriod.YEAR -> "год"
            StatsPeriod.ALL -> "всё время"
        }
}

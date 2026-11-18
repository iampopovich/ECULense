package io.github.iampopovich.eculense.feature.stats

import io.github.iampopovich.eculense.core.model.StatsPeriod
import io.github.iampopovich.eculense.core.model.UnitsSettings
import java.util.Locale

internal object StatsFormat {
    private val ru = Locale.forLanguageTag("ru")

    /** Разделитель тысяч — всегда неразрывный пробел (ICU даёт то U+00A0, то узкий U+202F). */
    fun number(v: Double, decimals: Int = 1): String = String.format(ru, "%,.${decimals}f", v).replace('\u202F', '\u00A0')

    fun distance(km: Double, units: UnitsSettings): String = units.distance(km).let { (v, u) -> "${number(v)} $u" }

    fun volume(l: Double, units: UnitsSettings): String = units.volume(l).let { (v, u) -> "${number(v)} $u" }

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

    /** Порядок в переключателе — от короткого периода к длинному. */
    val periods = listOf(StatsPeriod.WEEK, StatsPeriod.MONTH, StatsPeriod.YEAR, StatsPeriod.ALL)
}

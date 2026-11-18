package io.github.iampopovich.eculense.feature.car

import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.FuelType
import io.github.iampopovich.eculense.core.model.Gearbox
import io.github.iampopovich.eculense.core.model.StatsPeriod
import io.github.iampopovich.eculense.core.model.UnitsSettings
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

internal object CarFormat {
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

    fun month(m: YearMonth): String = m.month.getDisplayName(TextStyle.FULL_STANDALONE, ru).replaceFirstChar { it.uppercase() } + " " + m.year

    /** «Kia · Sorento · BL · 2005». */
    fun title(car: CarProfile): String = listOfNotNull(car.brand, car.model, car.generation, car.year?.toString()).joinToString(" · ")

    fun engine(car: CarProfile): String = listOfNotNull(
        car.engineCode,
        car.engineVolumeL?.let { "${number(it)} л" },
        car.cylinders?.let { "$it цил." },
        car.fuelType?.label,
        car.gearbox?.label,
    ).joinToString(" · ")

    val StatsPeriod.label: String
        get() = when (this) {
            StatsPeriod.ALL -> "Всё время"
            StatsPeriod.YEAR -> "Год"
            StatsPeriod.MONTH -> "30 дней"
            StatsPeriod.WEEK -> "7 дней"
        }
}

internal val FuelType.label: String
    get() = when (this) {
        FuelType.PETROL -> "Бензин"
        FuelType.DIESEL -> "Дизель"
        FuelType.LPG -> "Пропан"
        FuelType.CNG -> "Метан"
        FuelType.HYBRID -> "Гибрид"
        FuelType.ELECTRIC -> "Электро"
        FuelType.OTHER -> "Другое"
    }

internal val Gearbox.label: String
    get() = when (this) {
        Gearbox.MANUAL -> "Механика"
        Gearbox.AUTOMATIC -> "Автомат"
        Gearbox.CVT -> "Вариатор"
        Gearbox.ROBOT -> "Робот"
        Gearbox.OTHER -> "Другая"
    }

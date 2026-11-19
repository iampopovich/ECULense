package io.github.iampopovich.eculense.feature.car

import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.DriveType
import io.github.iampopovich.eculense.core.model.FuelType
import io.github.iampopovich.eculense.core.model.Gearbox
import java.util.Locale

internal object CarFormat {
    private val ru = Locale.forLanguageTag("ru")

    /** Thousands separator is always a non-breaking space (ICU yields either U+00A0 or narrow U+202F). */
    fun number(v: Double, decimals: Int = 1): String = String.format(ru, "%,.${decimals}f", v).replace('\u202F', '\u00A0')

    /** «Kia · Sorento · BL · 2005». */
    fun title(car: CarProfile): String = listOfNotNull(car.brand, car.model, car.generation, car.year?.toString()).joinToString(" · ")

    fun engine(car: CarProfile): String = listOfNotNull(
        car.engineCode,
        car.engineVolumeL?.let { "${number(it)} л" },
        car.cylinders?.let { "$it цил." },
        car.fuelType?.label,
        car.gearbox?.label,
        car.drive?.let { "${it.label} привод" },
    ).joinToString(" · ")
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

internal val DriveType.label: String
    get() = when (this) {
        DriveType.FRONT -> "Передний"
        DriveType.REAR -> "Задний"
        DriveType.ALL -> "Полный"
    }

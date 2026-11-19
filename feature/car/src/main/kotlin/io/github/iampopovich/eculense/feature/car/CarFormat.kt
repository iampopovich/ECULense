package io.github.iampopovich.eculense.feature.car

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.DriveType
import io.github.iampopovich.eculense.core.model.FuelType
import io.github.iampopovich.eculense.core.model.Gearbox
import java.util.Locale

internal object CarFormat {
    /** Thousands separator is always a non-breaking space (ICU yields either U+00A0 or narrow U+202F). */
    fun number(v: Double, decimals: Int = 1): String = String.format(Locale.getDefault(), "%,.${decimals}f", v).replace('\u202F', '\u00A0')

    /** «Kia · Sorento · BL · 2005». */
    fun title(car: CarProfile): String = listOfNotNull(car.brand, car.model, car.generation, car.year?.toString()).joinToString(" · ")

    @Composable
    fun engine(car: CarProfile): String = listOfNotNull(
        car.engineCode,
        car.engineVolumeL?.let { stringResource(R.string.car_engine_liters, number(it)) },
        car.cylinders?.let { stringResource(R.string.car_engine_cylinders, it) },
        car.fuelType?.let { stringResource(it.labelRes) },
        car.gearbox?.let { stringResource(it.labelRes) },
        car.drive?.let { stringResource(R.string.car_drive_suffix, stringResource(it.labelRes)) },
    ).joinToString(" · ")
}

@get:StringRes
internal val FuelType.labelRes: Int
    get() = when (this) {
        FuelType.PETROL -> R.string.car_fuel_petrol
        FuelType.DIESEL -> R.string.car_fuel_diesel
        FuelType.LPG -> R.string.car_fuel_lpg
        FuelType.CNG -> R.string.car_fuel_cng
        FuelType.HYBRID -> R.string.car_fuel_hybrid
        FuelType.ELECTRIC -> R.string.car_fuel_electric
        FuelType.OTHER -> R.string.car_fuel_other
    }

@get:StringRes
internal val Gearbox.labelRes: Int
    get() = when (this) {
        Gearbox.MANUAL -> R.string.car_gearbox_manual
        Gearbox.AUTOMATIC -> R.string.car_gearbox_automatic
        Gearbox.CVT -> R.string.car_gearbox_cvt
        Gearbox.ROBOT -> R.string.car_gearbox_robot
        Gearbox.OTHER -> R.string.car_gearbox_other
    }

@get:StringRes
internal val DriveType.labelRes: Int
    get() = when (this) {
        DriveType.FRONT -> R.string.car_drive_front
        DriveType.REAR -> R.string.car_drive_rear
        DriveType.ALL -> R.string.car_drive_all
    }

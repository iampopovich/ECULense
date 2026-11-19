package io.github.iampopovich.eculense.core.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.ConsumptionUnit
import io.github.iampopovich.eculense.core.model.DistanceUnit
import io.github.iampopovich.eculense.core.model.VolumeUnit

/** Car name for display: a car the user never named (blank or the legacy default) gets the localized default name. */
object CarNames {
    @Composable
    fun display(name: String): String = if (CarProfile.isDefaultName(name)) stringResource(R.string.common_car_default_name) else name

    @Composable
    fun display(car: CarProfile): String = display(car.name)

    fun display(context: Context, name: String): String =
        if (CarProfile.isDefaultName(name)) context.getString(R.string.common_car_default_name) else name
}

@get:StringRes
val DistanceUnit.labelRes: Int
    get() = when (this) {
        DistanceUnit.KM -> R.string.common_unit_km
        DistanceUnit.MI -> R.string.common_unit_mi
    }

@get:StringRes
val VolumeUnit.labelRes: Int
    get() = when (this) {
        VolumeUnit.L -> R.string.common_unit_l
        VolumeUnit.GAL_US -> R.string.common_unit_gal_us
        VolumeUnit.GAL_IMP -> R.string.common_unit_gal_imp
    }

@get:StringRes
val ConsumptionUnit.labelRes: Int
    get() = when (this) {
        ConsumptionUnit.L_PER_100KM -> R.string.common_unit_l_per_100km
        ConsumptionUnit.MPG_US -> R.string.common_unit_mpg_us
        ConsumptionUnit.MPG_IMP -> R.string.common_unit_mpg_imp
    }

package io.github.iampopovich.eculense.feature.car

import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.FuelType
import io.github.iampopovich.eculense.core.model.Gearbox
import io.github.iampopovich.eculense.core.model.ServiceIntervals

internal enum class CarField { NAME, YEAR, ENGINE_VOLUME, CYLINDERS, TANK, ODOMETER, VIN, FUEL_PRICE, CURRENCY, OIL, PLUGS, AIR_FILTER, FUEL_FILTER }

/**
 * Форма редактора: всё — строки, как их вводит пользователь. Ошибки считаются сразу (предупреждение ошибок по Нильсену):
 * кнопка «Сохранить» недоступна, пока есть хоть одна, а поле подсвечивает причину.
 */
internal data class CarForm(
    val id: Long = 0,
    val externalId: String? = null,
    val diagProfile: String? = null,
    val name: String = "",
    val brand: String = "",
    val model: String = "",
    val generation: String = "",
    val year: String = "",
    val engineCode: String = "",
    val engineVolumeL: String = "",
    val cylinders: String = "",
    val fuelType: FuelType? = null,
    val gearbox: Gearbox? = null,
    val tankVolumeL: String = "",
    val odometerKm: String = "",
    val odometerAtMs: Long? = null,
    val vin: String = "",
    val fuelPrice: String = "",
    val currency: String = "",
    val oilKm: String = "",
    val sparkPlugsKm: String = "",
    val airFilterKm: String = "",
    val fuelFilterKm: String = "",
) {
    fun errors(currentYear: Int): Map<CarField, String> = buildMap {
        if (name.isBlank()) put(CarField.NAME, "Укажите название")
        checkInt(year, 1900, currentYear + 1)?.let { put(CarField.YEAR, it) }
        checkDouble(engineVolumeL, 0.1, 10.0)?.let { put(CarField.ENGINE_VOLUME, it) }
        checkInt(cylinders, 1, 16)?.let { put(CarField.CYLINDERS, it) }
        checkDouble(tankVolumeL, 5.0, 300.0)?.let { put(CarField.TANK, it) }
        checkInt(odometerKm, 0, 5_000_000)?.let { put(CarField.ODOMETER, it) }
        if (vin.isNotBlank() && !VIN.matches(vin.trim().uppercase())) put(CarField.VIN, "VIN — 17 символов: латиница без I, O, Q и цифры")
        checkDouble(fuelPrice, 0.01, 100_000.0)?.let { put(CarField.FUEL_PRICE, it) }
        if (currency.isNotBlank() && !CURRENCY.matches(currency.trim().uppercase())) put(CarField.CURRENCY, "Код валюты из 3 букв, например RUB")
        checkInt(oilKm, 500, 200_000)?.let { put(CarField.OIL, it) }
        checkInt(sparkPlugsKm, 500, 300_000)?.let { put(CarField.PLUGS, it) }
        checkInt(airFilterKm, 500, 200_000)?.let { put(CarField.AIR_FILTER, it) }
        checkInt(fuelFilterKm, 500, 300_000)?.let { put(CarField.FUEL_FILTER, it) }
    }

    /** null, если есть ошибки. Дата пробега обновляется, только когда пользователь поменял сам пробег. */
    fun toProfile(original: CarProfile?, nowMs: Long, currentYear: Int): CarProfile? {
        if (errors(currentYear).isNotEmpty()) return null
        val odometer = odometerKm.toLongOrNullTrim()
        val odometerAt = when {
            odometer == null -> null
            odometer == original?.odometerKm -> original.odometerAtMs ?: nowMs
            else -> nowMs
        }
        return CarProfile(
            id = id,
            name = name.trim(),
            externalId = externalId,
            brand = brand.nullIfBlank(),
            model = model.nullIfBlank(),
            generation = generation.nullIfBlank(),
            year = year.toIntOrNullTrim(),
            engineCode = engineCode.nullIfBlank(),
            engineVolumeL = engineVolumeL.toDoubleOrNullLocal(),
            cylinders = cylinders.toIntOrNullTrim(),
            fuelType = fuelType,
            gearbox = gearbox,
            tankVolumeL = tankVolumeL.toDoubleOrNullLocal(),
            odometerKm = odometer,
            odometerAtMs = odometerAt,
            vin = vin.nullIfBlank()?.uppercase(),
            fuelPrice = fuelPrice.toDoubleOrNullLocal(),
            currency = currency.nullIfBlank()?.uppercase(),
            diagProfile = diagProfile,
            service = ServiceIntervals(oilKm.toIntOrNullTrim(), sparkPlugsKm.toIntOrNullTrim(), airFilterKm.toIntOrNullTrim(), fuelFilterKm.toIntOrNullTrim()),
        )
    }

    companion object {
        private val VIN = Regex("[A-HJ-NPR-Z0-9]{17}")
        private val CURRENCY = Regex("[A-Z]{3}")

        fun from(car: CarProfile) = CarForm(
            id = car.id,
            externalId = car.externalId,
            diagProfile = car.diagProfile,
            name = car.name,
            brand = car.brand.orEmpty(),
            model = car.model.orEmpty(),
            generation = car.generation.orEmpty(),
            year = car.year?.toString().orEmpty(),
            engineCode = car.engineCode.orEmpty(),
            engineVolumeL = car.engineVolumeL?.let(::plain).orEmpty(),
            cylinders = car.cylinders?.toString().orEmpty(),
            fuelType = car.fuelType,
            gearbox = car.gearbox,
            tankVolumeL = car.tankVolumeL?.let(::plain).orEmpty(),
            odometerKm = car.odometerKm?.toString().orEmpty(),
            odometerAtMs = car.odometerAtMs,
            vin = car.vin.orEmpty(),
            fuelPrice = car.fuelPrice?.let(::plain).orEmpty(),
            currency = car.currency.orEmpty(),
            oilKm = car.service.oilKm?.toString().orEmpty(),
            sparkPlugsKm = car.service.sparkPlugsKm?.toString().orEmpty(),
            airFilterKm = car.service.airFilterKm?.toString().orEmpty(),
            fuelFilterKm = car.service.fuelFilterKm?.toString().orEmpty(),
        )

        /** 57.3 → «57.3», 2.0 → «2». */
        private fun plain(v: Double): String = if (v == Math.floor(v) && v < 1e9) v.toLong().toString() else v.toString()
    }
}

private fun String.nullIfBlank(): String? = trim().ifBlank { null }
private fun String.toIntOrNullTrim(): Int? = trim().replace(" ", "").toIntOrNull()
private fun String.toLongOrNullTrim(): Long? = trim().replace(" ", "").toLongOrNull()
internal fun String.toDoubleOrNullLocal(): Double? = trim().replace(" ", "").replace(',', '.').toDoubleOrNull()

private fun checkInt(s: String, min: Int, max: Int): String? {
    if (s.isBlank()) return null
    val v = s.trim().replace(" ", "").toLongOrNull() ?: return "Только целое число"
    return if (v < min || v > max) "От $min до $max" else null
}

private fun checkDouble(s: String, min: Double, max: Double): String? {
    if (s.isBlank()) return null
    val v = s.toDoubleOrNullLocal() ?: return "Число, например 2,5"
    return if (v < min || v > max) "От ${fmt(min)} до ${fmt(max)}" else null
}

private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString().replace('.', ',')

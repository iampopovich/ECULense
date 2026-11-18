package io.github.iampopovich.eculense.core.model

enum class FuelType { PETROL, DIESEL, LPG, CNG, HYBRID, ELECTRIC, OTHER }

enum class Gearbox { MANUAL, AUTOMATIC, CVT, ROBOT, OTHER }

enum class DriveType { FRONT, REAR, ALL }

/**
 * Профиль автомобиля. Заполняется вручную или импортом из Car Scanner (тогда externalId = id машины в бэкапе).
 * VIN хранится только локально и никогда не уходит в промпты ИИ: для этого есть [withoutVin].
 */
data class CarProfile(
    val id: Long = 0,
    val name: String,
    val externalId: String? = null,
    val brand: String? = null,
    val model: String? = null,
    val generation: String? = null,
    val year: Int? = null,
    val engineCode: String? = null,
    val engineVolumeL: Double? = null,
    val cylinders: Int? = null,
    val fuelType: FuelType? = null,
    val gearbox: Gearbox? = null,
    val drive: DriveType? = null,
    val tankVolumeL: Double? = null,
    /** Снаряжённая масса, кг (справочник или вручную). */
    val curbWeightKg: Int? = null,
    val odometerKm: Long? = null,
    val odometerAtMs: Long? = null,
    val vin: String? = null,
    val fuelPrice: Double? = null,
    val currency: String? = null,
    /** Профиль диагностики Car Scanner («OBD-II / EOBD»), информационно. */
    val diagProfile: String? = null,
    val service: ServiceIntervals = ServiceIntervals(),
) {
    /**
     * Машина по умолчанию, созданная миграцией/первым запуском и ещё не тронутая пользователем.
     * Импорт Car Scanner заполняет её, а не создаёт вторую машину.
     */
    val isUntouchedDefault: Boolean
        get() = externalId == null && name == DEFAULT_NAME && brand == null && model == null && year == null && vin == null

    fun withoutVin(): CarProfile = copy(vin = null)

    companion object {
        const val DEFAULT_NAME = "Мой автомобиль"
    }
}

/** Интервалы ТО в километрах (используются аналитикой этапа 3). */
data class ServiceIntervals(
    val oilKm: Int? = null,
    val sparkPlugsKm: Int? = null,
    val airFilterKm: Int? = null,
    val fuelFilterKm: Int? = null,
)

enum class TripSource { LIVE, IMPORT_CARSCANNER }

/** Поездка как в Car Scanner (таблица trips): итоги без трека. */
data class Trip(
    val id: Long = 0,
    val carId: Long,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val distanceKm: Double,
    val fuelL: Double,
    val fuelPricePerL: Double?,
    val drivingMs: Long,
    val standingMs: Long,
    val source: TripSource,
    val externalId: String? = null,
)

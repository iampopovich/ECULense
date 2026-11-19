package io.github.iampopovich.eculense.core.model

enum class FuelType { PETROL, DIESEL, LPG, CNG, HYBRID, ELECTRIC, OTHER }

enum class Gearbox { MANUAL, AUTOMATIC, CVT, ROBOT, OTHER }

enum class DriveType { FRONT, REAR, ALL }

/**
 * Car profile. Filled in manually or imported from Car Scanner (then externalId = car id in the backup).
 * The VIN is stored only locally and never goes into AI prompts: that's what [withoutVin] is for.
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
    /** Curb weight, kg (reference database or manual). */
    val curbWeightKg: Int? = null,
    val odometerKm: Long? = null,
    val odometerAtMs: Long? = null,
    val vin: String? = null,
    val fuelPrice: Double? = null,
    val currency: String? = null,
    /** Car Scanner diagnostic profile («OBD-II / EOBD»), informational. */
    val diagProfile: String? = null,
    val service: ServiceIntervals = ServiceIntervals(),
) {
    /**
     * Default car created by a migration/first launch and not yet touched by the user.
     * Car Scanner import fills it in instead of creating a second car.
     */
    val isUntouchedDefault: Boolean
        get() = externalId == null && name == DEFAULT_NAME && brand == null && model == null && year == null && vin == null

    fun withoutVin(): CarProfile = copy(vin = null)

    companion object {
        const val DEFAULT_NAME = "Мой автомобиль"
    }
}

/** Maintenance intervals in kilometers (used by stage 3 analytics). */
data class ServiceIntervals(
    val oilKm: Int? = null,
    val sparkPlugsKm: Int? = null,
    val airFilterKm: Int? = null,
    val fuelFilterKm: Int? = null,
)

/** LIVE: computed by ECULense from a recording (own, or imported without a Car Scanner trip). */
enum class TripSource { LIVE, IMPORT_CARSCANNER }

/** Where the fuel of a trip computed from a recording came from. null: trip from Car Scanner (it has its own). */
enum class FuelMethod {
    /** PID 01.5E: engine fuel rate, L/h. */
    OBD_FUEL_RATE,

    /** MAF-based estimate: gasoline stoichiometry 14.7 and density 0.745 kg/L. */
    MAF_ESTIMATE,

    /** Nothing to compute from (diesel/LPG, no MAF and 01.5E): fuelL = 0, excluded from consumption. */
    NOT_AVAILABLE,
}

/** Trip as in Car Scanner (trips table): totals without a track. */
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
    val fuelMethod: FuelMethod? = null,
) {
    val fuelKnown: Boolean get() = fuelMethod != FuelMethod.NOT_AVAILABLE
}

/** Mileage: MANUAL = entered by the user (or carried over from the profile/Car Scanner); ESTIMATED = estimated from trips. */
enum class OdometerSource { MANUAL, ESTIMATED }

data class OdometerEntry(
    val id: Long = 0,
    val carId: Long,
    val km: Long,
    val atMs: Long,
    val source: OdometerSource = OdometerSource.MANUAL,
)

/**
 * Maintenance item. Intervals by mileage and/or time (null: not set). lastDone*: when it was last done
 * (null: unknown, status «отметьте, когда делали»).
 */
data class MaintenanceItem(
    val id: Long = 0,
    val carId: Long,
    val title: String,
    val intervalKm: Int? = null,
    val intervalMonths: Int? = null,
    val lastDoneKm: Long? = null,
    val lastDoneAtMs: Long? = null,
    val note: String? = null,
)

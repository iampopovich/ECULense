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
        get() = externalId == null && hasDefaultName && brand == null && model == null && year == null && vin == null

    /** The name was never set by the user: the UI shows the localized «car_default_name» instead. */
    val hasDefaultName: Boolean get() = isDefaultName(name)

    fun withoutVin(): CarProfile = copy(vin = null)

    companion object {
        /** New default cars get a blank name; the UI substitutes a localized one. */
        const val DEFAULT_NAME = ""

        /** Blank, or the Russian literal that pre-localization versions stored in the DB. */
        fun isDefaultName(name: String): Boolean = name.isBlank() || name.trim() == LegacyValues.DEFAULT_CAR_NAME_RU
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

/** Standard maintenance works of the template; names are string resources, English names for the AI are in :core:analytics. */
enum class MaintenanceKind {
    OIL_AND_FILTER, AIR_FILTER, SPARK_PLUGS, FUEL_FILTER, COOLANT, BRAKE_FLUID, TIMING_BELT, CVT_OIL, AUTOMATIC_TRANSMISSION_OIL,
}

/** Standard notes of the template. */
enum class MaintenanceNote { OIL_SEVERE_CONDITIONS, SPARK_PLUGS_TYPE, TIMING_INTERVAL }

/**
 * Maintenance item. Intervals by mileage and/or time (null: not set). lastDone*: when it was last done
 * (null: unknown, status «mark when it was done»).
 * Template items have a [kind] and a blank [title]: the UI shows the localized name. A non-blank title is the user's own
 * name and wins. Likewise [note] (user text) wins over [noteKind] (localized template note).
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
    val kind: MaintenanceKind? = null,
    val noteKind: MaintenanceNote? = null,
)

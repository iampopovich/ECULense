package io.github.iampopovich.eculense.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import io.github.iampopovich.eculense.core.database.CarDao
import io.github.iampopovich.eculense.core.database.CarEntity
import io.github.iampopovich.eculense.core.database.TripDao
import io.github.iampopovich.eculense.core.database.TripEntity
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.DriveType
import io.github.iampopovich.eculense.core.model.FuelType
import io.github.iampopovich.eculense.core.model.Gearbox
import io.github.iampopovich.eculense.core.model.ServiceIntervals
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import io.github.iampopovich.eculense.core.model.UnitsSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.roundToLong

/** Машины пользователя; одна из них активна — к ней привязываются новые записи, DTC и импорт без своей машины. */
interface CarRepository {
    fun observeCars(): Flow<List<CarProfile>>
    fun observeActiveCar(): Flow<CarProfile?>
    suspend fun activeCarId(): Long?
    suspend fun cars(): List<CarProfile>
    suspend fun byExternalId(externalId: String): CarProfile?
    /** id == 0 → новая машина. Возвращает id. */
    suspend fun save(car: CarProfile): Long
    /** Последнюю машину удалить нельзя: записям нужна машина по умолчанию. */
    suspend fun delete(id: Long): Boolean
    suspend fun setActive(id: Long)
}

interface TripRepository {
    fun observeTrips(carId: Long): Flow<List<Trip>>
    /** Возвращает, сколько поездок добавлено (повторы по externalId пропускаются). */
    suspend fun addAll(trips: List<Trip>): Int
}

interface UnitsRepository {
    val units: Flow<UnitsSettings>
    suspend fun save(units: UnitsSettings)
}

/** Активная машина и единицы измерения — мелкие настройки в DataStore. */
interface VehiclePrefs {
    val activeCarId: Flow<Long?>
    suspend fun setActiveCarId(id: Long)
}

internal class DataStoreVehiclePrefs(private val store: DataStore<Preferences>) : VehiclePrefs, UnitsRepository {
    override val activeCarId: Flow<Long?> = store.data.map { it[ACTIVE_CAR] }

    override suspend fun setActiveCarId(id: Long) {
        store.edit { it[ACTIVE_CAR] = id }
    }

    override val units: Flow<UnitsSettings> = store.data.map { p ->
        val d = UnitsSettings()
        UnitsSettings(
            metricDistance = p[KM] ?: d.metricDistance,
            celsius = p[CELSIUS] ?: d.celsius,
            litersPer100Km = p[L100] ?: d.litersPer100Km,
            liters = p[LITERS] ?: d.liters,
            usGallon = p[US_GALLON] ?: d.usGallon,
            pressureKpa = p[KPA] ?: d.pressureKpa,
            horsePower = p[HP] ?: d.horsePower,
            newtonMeters = p[NM] ?: d.newtonMeters,
            flowGramsPerSecond = p[GS] ?: d.flowGramsPerSecond,
            accelerationG = p[G] ?: d.accelerationG,
        )
    }

    override suspend fun save(units: UnitsSettings) {
        store.edit { p ->
            p[KM] = units.metricDistance
            p[CELSIUS] = units.celsius
            p[L100] = units.litersPer100Km
            p[LITERS] = units.liters
            p[US_GALLON] = units.usGallon
            p[KPA] = units.pressureKpa
            p[HP] = units.horsePower
            p[NM] = units.newtonMeters
            p[GS] = units.flowGramsPerSecond
            p[G] = units.accelerationG
        }
    }

    private companion object {
        val ACTIVE_CAR = longPreferencesKey("active_car_id")
        val KM = booleanPreferencesKey("units_km")
        val CELSIUS = booleanPreferencesKey("units_celsius")
        val L100 = booleanPreferencesKey("units_l100")
        val LITERS = booleanPreferencesKey("units_liters")
        val US_GALLON = booleanPreferencesKey("units_us_gallon")
        val KPA = booleanPreferencesKey("units_kpa")
        val HP = booleanPreferencesKey("units_hp")
        val NM = booleanPreferencesKey("units_nm")
        val GS = booleanPreferencesKey("units_flow_gs")
        val G = booleanPreferencesKey("units_accel_g")
    }
}

internal class RoomCarRepository(private val dao: CarDao, private val prefs: VehiclePrefs) : CarRepository {
    override fun observeCars(): Flow<List<CarProfile>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    /** Сохранённый id, если такая машина ещё есть; иначе — первая машина. */
    override fun observeActiveCar(): Flow<CarProfile?> =
        combine(observeCars(), prefs.activeCarId) { cars, id -> cars.firstOrNull { it.id == id } ?: cars.firstOrNull() }

    override suspend fun activeCarId(): Long? = observeActiveCar().first()?.id

    override suspend fun cars(): List<CarProfile> = dao.all().map { it.toModel() }

    override suspend fun byExternalId(externalId: String): CarProfile? = dao.byExternalId(externalId)?.toModel()

    override suspend fun save(car: CarProfile): Long =
        if (car.id == 0L) dao.insert(car.toEntity()) else car.id.also { dao.update(car.toEntity()) }

    override suspend fun delete(id: Long): Boolean {
        if (dao.all().size <= 1) return false
        dao.detachSessions(id)
        dao.detachDtc(id)
        dao.delete(id)
        return true
    }

    override suspend fun setActive(id: Long) = prefs.setActiveCarId(id)
}

internal class RoomTripRepository(private val dao: TripDao) : TripRepository {
    override fun observeTrips(carId: Long): Flow<List<Trip>> = dao.observeForCar(carId).map { list -> list.map { it.toModel() } }

    override suspend fun addAll(trips: List<Trip>): Int = dao.insertAll(trips.map { it.toEntity() }).count { it > 0 }
}

internal fun CarEntity.toModel() = CarProfile(
    id = id, name = name, externalId = externalId, brand = brand, model = model, generation = generation, year = year,
    engineCode = engineCode, engineVolumeL = engineVolumeL, cylinders = cylinders,
    fuelType = fuelType?.let { runCatching { FuelType.valueOf(it) }.getOrNull() },
    gearbox = gearbox?.let { runCatching { Gearbox.valueOf(it) }.getOrNull() },
    drive = drive?.let { runCatching { DriveType.valueOf(it) }.getOrNull() },
    curbWeightKg = curbWeightKg,
    tankVolumeL = tankVolumeL, odometerKm = odometerKm, odometerAtMs = odometerAtMs, vin = vin, fuelPrice = fuelPrice,
    currency = currency, diagProfile = diagProfile,
    service = ServiceIntervals(oilIntervalKm, sparkPlugsIntervalKm, airFilterIntervalKm, fuelFilterIntervalKm),
)

internal fun CarProfile.toEntity() = CarEntity(
    id = id, name = name, externalId = externalId, brand = brand, model = model, generation = generation, year = year,
    engineCode = engineCode, engineVolumeL = engineVolumeL, cylinders = cylinders, fuelType = fuelType?.name, gearbox = gearbox?.name,
    tankVolumeL = tankVolumeL, odometerKm = odometerKm, odometerAtMs = odometerAtMs, vin = vin, fuelPrice = fuelPrice,
    currency = currency, diagProfile = diagProfile, oilIntervalKm = service.oilKm, sparkPlugsIntervalKm = service.sparkPlugsKm,
    airFilterIntervalKm = service.airFilterKm, fuelFilterIntervalKm = service.fuelFilterKm,
    drive = drive?.name, curbWeightKg = curbWeightKg,
)

internal fun TripEntity.toModel() = Trip(
    id = id, carId = carId, startedAtMs = startedAtMs, endedAtMs = endedAtMs, distanceKm = distanceMm / 1_000_000.0,
    fuelL = fuelUl / 1_000_000.0, fuelPricePerL = fuelPricePerL, drivingMs = drivingMs, standingMs = standingMs,
    source = runCatching { TripSource.valueOf(source) }.getOrDefault(TripSource.LIVE), externalId = externalId,
)

internal fun Trip.toEntity() = TripEntity(
    id = id, carId = carId, startedAtMs = startedAtMs, endedAtMs = endedAtMs, distanceMm = (distanceKm * 1_000_000).roundToLong(),
    fuelUl = (fuelL * 1_000_000).roundToLong(), fuelPricePerL = fuelPricePerL, drivingMs = drivingMs, standingMs = standingMs,
    source = source.name, externalId = externalId,
)

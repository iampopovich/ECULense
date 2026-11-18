package io.github.iampopovich.eculense.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import io.github.iampopovich.eculense.core.database.CarDao
import io.github.iampopovich.eculense.core.database.CarEntity
import io.github.iampopovich.eculense.core.database.OdometerDao
import io.github.iampopovich.eculense.core.database.OdometerEntity
import io.github.iampopovich.eculense.core.database.TripDao
import io.github.iampopovich.eculense.core.database.TripEntity
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.DriveType
import io.github.iampopovich.eculense.core.model.FuelMethod
import io.github.iampopovich.eculense.core.model.FuelType
import io.github.iampopovich.eculense.core.model.Gearbox
import io.github.iampopovich.eculense.core.model.OdometerSource
import io.github.iampopovich.eculense.core.model.ServiceIntervals
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import io.github.iampopovich.eculense.core.model.UnitsSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
    suspend fun trips(carId: Long): List<Trip> = observeTrips(carId).first()
    /** Возвращает, сколько поездок добавлено (повторы по externalId пропускаются). */
    suspend fun addAll(trips: List<Trip>): Int
    /**
     * Поездки из бэкапа (1.27): новые добавляются, уже известные (тот же externalId) обновляются, только если в бэкапе
     * изменились итоги (Car Scanner дописал поездку); машина и id существующей поездки сохраняются.
     */
    suspend fun mergeImported(trips: List<Trip>): TripMerge {
        val added = addAll(trips)
        return TripMerge(added = added, updated = 0, unchanged = trips.size - added)
    }
}

data class TripMerge(val added: Int, val updated: Int, val unchanged: Int)

/** Итоги поездки совпадают (в единицах хранения: мм, мкл, мс). */
internal fun TripEntity.sameTotalsAs(o: TripEntity): Boolean =
    endedAtMs == o.endedAtMs && distanceMm == o.distanceMm && fuelUl == o.fuelUl && fuelPricePerL == o.fuelPricePerL &&
        drivingMs == o.drivingMs && standingMs == o.standingMs

/** Флаг «типовой список ТО уже предлагался»: удалённые пользователем работы не возвращаются сами. */
interface MaintenancePrefs {
    suspend fun templateSeeded(carId: Long): Boolean
    suspend fun markTemplateSeeded(carId: Long)
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

internal class DataStoreVehiclePrefs(private val store: DataStore<Preferences>) : VehiclePrefs, UnitsRepository, MaintenancePrefs {
    override val activeCarId: Flow<Long?> = store.data.map { it[ACTIVE_CAR] }

    override suspend fun setActiveCarId(id: Long) {
        store.edit { it[ACTIVE_CAR] = id }
    }

    override suspend fun templateSeeded(carId: Long): Boolean = store.data.first()[seededKey(carId)] == true

    override suspend fun markTemplateSeeded(carId: Long) {
        store.edit { it[seededKey(carId)] = true }
    }

    private fun seededKey(carId: Long) = booleanPreferencesKey("maintenance_seeded_$carId")

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

/**
 * Пробег профиля (odometerKm/odometerAtMs) живёт в журнале odometer_log (v5): профиль показывает последний ручной
 * ввод, а сохранение профиля с новым пробегом добавляет запись в журнал. Колонки cars.odometer* больше не пишутся.
 */
internal class RoomCarRepository(
    private val dao: CarDao,
    private val prefs: VehiclePrefs,
    private val odometer: OdometerDao? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : CarRepository {
    private val latestOdometer: Flow<Map<Long, OdometerEntity>> =
        odometer?.observeLatestManual()?.map { list -> list.associateBy { it.carId } } ?: flowOf(emptyMap())

    override fun observeCars(): Flow<List<CarProfile>> =
        combine(dao.observeAll(), latestOdometer) { list, odo -> list.map { it.toModel().withOdometer(odo[it.id]) } }

    /** Сохранённый id, если такая машина ещё есть; иначе — первая машина. */
    override fun observeActiveCar(): Flow<CarProfile?> =
        combine(observeCars(), prefs.activeCarId) { cars, id -> cars.firstOrNull { it.id == id } ?: cars.firstOrNull() }

    override suspend fun activeCarId(): Long? = observeActiveCar().first()?.id

    override suspend fun cars(): List<CarProfile> = latestOdometer.first().let { odo -> dao.all().map { it.toModel().withOdometer(odo[it.id]) } }

    override suspend fun byExternalId(externalId: String): CarProfile? =
        dao.byExternalId(externalId)?.let { e -> e.toModel().withOdometer(odometer?.latestManual(e.id)) }

    override suspend fun save(car: CarProfile): Long {
        val entity = car.toEntity().copy(odometerKm = null, odometerAtMs = null)
        val id = if (car.id == 0L) dao.insert(entity) else car.id.also { dao.update(entity) }
        val km = car.odometerKm
        val log = odometer
        if (km != null && log != null) {
            val latest = log.latestManual(id)
            if (latest == null || latest.km != km || (car.odometerAtMs != null && latest.atMs != car.odometerAtMs)) {
                log.insert(OdometerEntity(carId = id, km = km, atMs = car.odometerAtMs ?: clock(), source = OdometerSource.MANUAL.name))
            }
        }
        return id
    }

    private fun CarProfile.withOdometer(e: OdometerEntity?) = if (e == null) this else copy(odometerKm = e.km, odometerAtMs = e.atMs)

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

    override suspend fun trips(carId: Long): List<Trip> = dao.forCar(carId).map { it.toModel() }

    override suspend fun addAll(trips: List<Trip>): Int = dao.insertAll(trips.map { it.toEntity() }).count { it > 0 }

    override suspend fun mergeImported(trips: List<Trip>): TripMerge {
        val incoming = trips.map { it.toEntity() }
        val existing = incoming.mapNotNull { it.externalId }.chunked(500).flatMap { dao.byExternalIds(it) }.associateBy { it.externalId }
        val fresh = incoming.filter { it.externalId == null || it.externalId !in existing }
        val changed = incoming.mapNotNull { t ->
            val old = existing[t.externalId] ?: return@mapNotNull null
            if (old.sameTotalsAs(t)) null else t.copy(id = old.id, carId = old.carId, source = old.source, fuelMethod = old.fuelMethod)
        }
        val added = if (fresh.isEmpty()) 0 else dao.insertAll(fresh).count { it > 0 }
        if (changed.isNotEmpty()) dao.updateAll(changed)
        return TripMerge(added = added, updated = changed.size, unchanged = trips.size - added - changed.size)
    }
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
    fuelMethod = fuelMethod?.let { runCatching { FuelMethod.valueOf(it) }.getOrNull() },
)

internal fun Trip.toEntity() = TripEntity(
    id = id, carId = carId, startedAtMs = startedAtMs, endedAtMs = endedAtMs, distanceMm = (distanceKm * 1_000_000).roundToLong(),
    fuelUl = (fuelL * 1_000_000).roundToLong(), fuelPricePerL = fuelPricePerL, drivingMs = drivingMs, standingMs = standingMs,
    source = source.name, externalId = externalId, fuelMethod = fuelMethod?.name,
)

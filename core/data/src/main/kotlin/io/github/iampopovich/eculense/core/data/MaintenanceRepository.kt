package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import io.github.iampopovich.eculense.core.analytics.MaintenanceRule
import io.github.iampopovich.eculense.core.analytics.MaintenanceTemplate
import io.github.iampopovich.eculense.core.analytics.OdometerEstimate
import io.github.iampopovich.eculense.core.analytics.OdometerEstimator
import io.github.iampopovich.eculense.core.database.MaintenanceDao
import io.github.iampopovich.eculense.core.database.MaintenanceItemEntity
import io.github.iampopovich.eculense.core.database.OdometerDao
import io.github.iampopovich.eculense.core.database.OdometerEntity
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import io.github.iampopovich.eculense.core.model.OdometerEntry
import io.github.iampopovich.eculense.core.model.OdometerSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * Maintenance and mileage (3.9 item 2). Mileage = log of manual entries + estimate from trips after the last entry
 * ([OdometerEstimator]); task statuses — [MaintenanceRule]. Everything is local, no network.
 */
interface MaintenanceRepository {
    fun observeItems(carId: Long): Flow<List<MaintenanceItem>>
    suspend fun items(carId: Long): List<MaintenanceItem>
    /** Offer the default list once per car; true — the list was added just now. */
    suspend fun ensureTemplate(car: CarProfile): Boolean
    /** id == 0 → new task. Returns the id. «Отменить» also uses this to restore the task's previous state. */
    suspend fun save(item: MaintenanceItem): Long
    suspend fun delete(id: Long)
    /** Mark as «сделано»; returns the state before marking (for «Отменить»). */
    suspend fun markDone(id: Long, km: Long?, atMs: Long): MaintenanceItem?
    fun observeOdometer(carId: Long): Flow<List<OdometerEntry>>
    suspend fun addOdometer(carId: Long, km: Long, atMs: Long): Long
    suspend fun deleteOdometer(id: Long)
    fun observeEstimate(carId: Long): Flow<OdometerEstimate?>
    suspend fun estimate(carId: Long): OdometerEstimate?
    /** All tasks with statuses (overdue first). */
    suspend fun evaluate(carId: Long): List<MaintenanceDue>
    /** What is due: overdue or coming soon. */
    suspend fun due(carId: Long): List<MaintenanceDue>
}

internal class RoomMaintenanceRepository(
    private val dao: MaintenanceDao,
    private val odometer: OdometerDao,
    private val trips: TripRepository,
    private val prefs: MaintenancePrefs,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : MaintenanceRepository {
    private val seedMutex = Mutex()

    override fun observeItems(carId: Long): Flow<List<MaintenanceItem>> = dao.observeForCar(carId).map { l -> l.map { it.toModel() } }

    override suspend fun items(carId: Long): List<MaintenanceItem> = dao.forCar(carId).map { it.toModel() }

    override suspend fun ensureTemplate(car: CarProfile): Boolean = seedMutex.withLock {
        if (prefs.templateSeeded(car.id)) return false
        val seeded = dao.forCar(car.id).isEmpty()
        if (seeded) dao.insertAll(MaintenanceTemplate.forCar(car).map { it.toEntity() })
        prefs.markTemplateSeeded(car.id)
        seeded
    }

    override suspend fun save(item: MaintenanceItem): Long =
        if (item.id == 0L) dao.insert(item.toEntity()) else item.id.also { dao.update(item.toEntity()) }

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun markDone(id: Long, km: Long?, atMs: Long): MaintenanceItem? {
        val before = dao.byId(id) ?: return null
        dao.update(before.copy(lastDoneKm = km, lastDoneAtMs = atMs))
        return before.toModel()
    }

    override fun observeOdometer(carId: Long): Flow<List<OdometerEntry>> = odometer.observeForCar(carId).map { l -> l.map { it.toModel() } }

    override suspend fun addOdometer(carId: Long, km: Long, atMs: Long): Long =
        odometer.insert(OdometerEntity(carId = carId, km = km, atMs = atMs, source = OdometerSource.MANUAL.name))

    override suspend fun deleteOdometer(id: Long) = odometer.delete(id)

    override fun observeEstimate(carId: Long): Flow<OdometerEstimate?> =
        combine(observeOdometer(carId), trips.observeTrips(carId)) { log, list -> OdometerEstimator.estimate(log, list) }

    override suspend fun estimate(carId: Long): OdometerEstimate? =
        OdometerEstimator.estimate(odometer.forCar(carId).map { it.toModel() }, trips.trips(carId))

    override suspend fun evaluate(carId: Long): List<MaintenanceDue> = MaintenanceRule.evaluateAll(items(carId), estimate(carId)?.km, clock(), zone())

    override suspend fun due(carId: Long): List<MaintenanceDue> = MaintenanceRule.due(items(carId), estimate(carId)?.km, clock(), zone())
}

internal fun MaintenanceItemEntity.toModel() = MaintenanceItem(id, carId, title, intervalKm, intervalMonths, lastDoneKm, lastDoneAtMs, note)

internal fun MaintenanceItem.toEntity() = MaintenanceItemEntity(id, carId, title, intervalKm, intervalMonths, lastDoneKm, lastDoneAtMs, note)

internal fun OdometerEntity.toModel() = OdometerEntry(
    id, carId, km, atMs, runCatching { OdometerSource.valueOf(source) }.getOrDefault(OdometerSource.MANUAL),
)

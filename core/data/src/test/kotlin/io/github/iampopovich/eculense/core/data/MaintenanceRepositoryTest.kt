package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.analytics.MaintenanceStatus
import io.github.iampopovich.eculense.core.database.MaintenanceDao
import io.github.iampopovich.eculense.core.database.MaintenanceItemEntity
import io.github.iampopovich.eculense.core.database.OdometerDao
import io.github.iampopovich.eculense.core.database.OdometerEntity
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.FuelMethod
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

internal class FakeMaintenanceDao : MaintenanceDao {
    val rows = MutableStateFlow<List<MaintenanceItemEntity>>(emptyList())
    private var next = 1L
    override fun observeForCar(carId: Long): Flow<List<MaintenanceItemEntity>> = rows.map { l -> l.filter { it.carId == carId } }
    override suspend fun forCar(carId: Long) = rows.value.filter { it.carId == carId }
    override suspend fun byId(id: Long) = rows.value.firstOrNull { it.id == id }
    override suspend fun insert(item: MaintenanceItemEntity): Long = next++.also { rows.value = rows.value + item.copy(id = it) }
    override suspend fun insertAll(items: List<MaintenanceItemEntity>) = items.forEach { insert(it) }
    override suspend fun update(item: MaintenanceItemEntity) { rows.value = rows.value.map { if (it.id == item.id) item else it } }
    override suspend fun delete(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
}

internal class FakeOdometerDao : OdometerDao {
    val rows = MutableStateFlow<List<OdometerEntity>>(emptyList())
    private var next = 1L
    override fun observeForCar(carId: Long): Flow<List<OdometerEntity>> = rows.map { l -> l.filter { it.carId == carId }.sortedBy { it.atMs } }
    override suspend fun forCar(carId: Long) = rows.value.filter { it.carId == carId }.sortedBy { it.atMs }
    override fun observeLatestManual(): Flow<List<OdometerEntity>> = rows.map { l ->
        l.filter { it.source == "MANUAL" }.groupBy { it.carId }.values.map { g -> g.maxWith(compareBy({ it.atMs }, { it.id })) }
    }
    override suspend fun latestManual(carId: Long) = observeLatestManual().first().firstOrNull { it.carId == carId }
    override suspend fun insert(entry: OdometerEntity): Long = next++.also { rows.value = rows.value + entry.copy(id = it) }
    override suspend fun delete(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
}

internal class FakeSeedFlags : MaintenancePrefs {
    val seeded = mutableSetOf<Long>()
    override suspend fun templateSeeded(carId: Long) = carId in seeded
    override suspend fun markTemplateSeeded(carId: Long) { seeded += carId }
}

class MaintenanceRepositoryTest {
    private val zone = ZoneOffset.UTC
    private fun ms(date: String) = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
    private val now = ms("2026-06-01")
    private val car = CarProfile(id = 1, name = "Тестовая")
    private val dao = FakeMaintenanceDao()
    private val odo = FakeOdometerDao()
    private val trips = FakeTrips()
    private val flags = FakeSeedFlags()
    private val repo = RoomMaintenanceRepository(dao, odo, trips, flags, clock = { now }, zone = { zone })

    private fun trip(at: Long, km: Double, ext: String) = Trip(carId = 1, startedAtMs = at, endedAtMs = at + 1, distanceKm = km, fuelL = 0.0,
        fuelPricePerL = null, drivingMs = 1, standingMs = 0, source = TripSource.LIVE, externalId = ext, fuelMethod = FuelMethod.MAF_ESTIMATE)

    @Test
    fun `template is seeded once per car and not again after the user deletes everything`() = runTest {
        assertTrue(repo.ensureTemplate(car))
        val seeded = repo.items(1)
        assertTrue(seeded.any { it.title == "Масло и масляный фильтр двигателя" })
        assertFalse(repo.ensureTemplate(car))
        seeded.forEach { repo.delete(it.id) }
        assertFalse(repo.ensureTemplate(car))
        assertTrue(repo.items(1).isEmpty())
    }

    @Test
    fun `odometer estimate adds trips after the last manual entry`() = runTest {
        assertNull(repo.estimate(1))
        repo.addOdometer(1, 100_000, ms("2026-05-01"))
        trips.addAll(listOf(trip(ms("2026-04-20"), 50.0, "a"), trip(ms("2026-05-02"), 12.5, "b")))
        val e = repo.estimate(1)!!
        assertEquals(100_012.5, e.km, 1e-9)
        assertTrue(e.estimated)
        assertEquals(e, repo.observeEstimate(1).first())
    }

    @Test
    fun `mark done stores km and date and returns the previous state for undo`() = runTest {
        repo.addOdometer(1, 60_500, ms("2026-05-30"))
        val id = repo.save(io.github.iampopovich.eculense.core.model.MaintenanceItem(carId = 1, title = "Масло", intervalKm = 10_000, intervalMonths = 12, lastDoneKm = 50_000, lastDoneAtMs = ms("2025-07-01")))
        assertEquals(MaintenanceStatus.OVERDUE, repo.due(1).single().status)

        val previous = repo.markDone(id, km = 60_500, atMs = now)!!
        assertEquals(50_000L, previous.lastDoneKm)
        val done = repo.items(1).single()
        assertEquals(60_500L, done.lastDoneKm)
        assertEquals(now, done.lastDoneAtMs)
        assertTrue(repo.due(1).isEmpty())

        repo.save(previous)
        assertEquals(50_000L, repo.items(1).single().lastDoneKm)
    }

    @Test
    fun `deleting the odometer entry restores the previous state`() = runTest {
        val id = repo.addOdometer(1, 1_000, now)
        assertEquals(1_000.0, repo.estimate(1)!!.km, 0.0)
        repo.deleteOdometer(id)
        assertNull(repo.estimate(1))
        assertTrue(odo.rows.value.isEmpty())
    }
}

package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.database.CarDao
import io.github.iampopovich.eculense.core.database.CarEntity
import io.github.iampopovich.eculense.core.model.CarProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class MemCarDao : CarDao {
    val rows = MutableStateFlow(listOf(CarEntity(id = 1, name = "Тестовая")))
    override fun observeAll(): Flow<List<CarEntity>> = rows
    override suspend fun all() = rows.value
    override suspend fun byId(id: Long) = rows.value.firstOrNull { it.id == id }
    override suspend fun byExternalId(externalId: String) = rows.value.firstOrNull { it.externalId == externalId }
    override suspend fun insert(car: CarEntity): Long = (rows.value.maxOf { it.id } + 1).also { rows.value = rows.value + car.copy(id = it) }
    override suspend fun update(car: CarEntity) { rows.value = rows.value.map { if (it.id == car.id) car else it } }
    override suspend fun delete(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
    override suspend fun detachSessions(id: Long) = Unit
    override suspend fun detachDtc(id: Long) = Unit
}

private class NoPrefs : VehiclePrefs {
    override val activeCarId: Flow<Long?> = flowOf(null)
    override suspend fun setActiveCarId(id: Long) = Unit
}

/** Profile mileage is the latest manual entry in the odometer_log journal, not a cars column. */
class CarOdometerTest {
    private val dao = MemCarDao()
    private val log = FakeOdometerDao()
    private val repo = RoomCarRepository(dao, NoPrefs(), log, clock = { 5_000L })

    @Test
    fun `saving a new odometer value appends a manual log entry and keeps cars columns empty`() = runTest {
        repo.save(CarProfile(id = 1, name = "Тестовая", odometerKm = 1_000, odometerAtMs = 100))
        assertEquals(listOf(1_000L), log.rows.value.map { it.km })
        assertNull(dao.rows.value.single().odometerKm)
        assertEquals(1_000L to 100L, repo.cars().single().let { it.odometerKm to it.odometerAtMs })
        assertEquals(1_000L, repo.observeActiveCar().first()!!.odometerKm)
    }

    @Test
    fun `saving the same odometer again does not duplicate and a profile without one adds nothing`() = runTest {
        repo.save(CarProfile(id = 1, name = "Тестовая", odometerKm = 1_000, odometerAtMs = 100))
        repo.save(repo.cars().single().copy(name = "Тестовая 2"))
        repo.save(CarProfile(id = 1, name = "Тестовая 3"))
        assertEquals(1, log.rows.value.size)
        repo.save(CarProfile(id = 1, name = "Тестовая", odometerKm = 1_500))
        assertEquals(listOf(1_000L, 1_500L), log.rows.value.map { it.km })
        assertEquals(5_000L, log.rows.value.last().atMs)
    }
}

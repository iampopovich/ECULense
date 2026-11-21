package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.analytics.EngineTraits
import io.github.iampopovich.eculense.core.analytics.FuelKind
import io.github.iampopovich.eculense.core.analytics.HealthItem
import io.github.iampopovich.eculense.core.analytics.HealthItemId
import io.github.iampopovich.eculense.core.analytics.HealthNote
import io.github.iampopovich.eculense.core.analytics.HealthResult
import io.github.iampopovich.eculense.core.analytics.HealthStatus
import io.github.iampopovich.eculense.core.analytics.Norm
import io.github.iampopovich.eculense.core.analytics.NormKey
import io.github.iampopovich.eculense.core.analytics.NormSource
import io.github.iampopovich.eculense.core.analytics.Trait
import io.github.iampopovich.eculense.core.analytics.TripEventType
import io.github.iampopovich.eculense.core.database.CarNormDao
import io.github.iampopovich.eculense.core.database.CarNormEntity
import io.github.iampopovich.eculense.core.database.EventDao
import io.github.iampopovich.eculense.core.database.HealthCheckEntity
import io.github.iampopovich.eculense.core.database.HealthDao
import io.github.iampopovich.eculense.core.database.TripEventEntity
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemEventDao : EventDao {
    val rows = MutableStateFlow<List<TripEventEntity>>(emptyList())
    override suspend fun insert(event: TripEventEntity): Long = (rows.value.size + 1L).also { rows.value = rows.value + event.copy(id = it) }
    override suspend fun forSession(sessionId: Long) = rows.value.filter { it.sessionId == sessionId }.sortedBy { it.atMs }
    override fun observeForSession(sessionId: Long): Flow<List<TripEventEntity>> = rows.map { l -> l.filter { it.sessionId == sessionId }.sortedBy { it.atMs } }
    override suspend fun forCar(carId: Long, fromMs: Long, toMs: Long) = rows.value.filter { it.carId == carId && it.atMs in fromMs..toMs }.sortedBy { it.atMs }
}

private class MemHealthDao : HealthDao {
    val rows = MutableStateFlow<List<HealthCheckEntity>>(emptyList())
    override suspend fun insert(check: HealthCheckEntity): Long = (rows.value.size + 1L).also { rows.value = rows.value + check.copy(id = it) }
    override fun observeForCar(carId: Long?, limit: Int): Flow<List<HealthCheckEntity>> =
        rows.map { l -> l.filter { it.carId == carId }.sortedByDescending { it.takenAtMs }.take(limit) }
    override suspend fun byId(id: Long) = rows.value.firstOrNull { it.id == id }
}

private class MemNormDao : CarNormDao {
    val rows = MutableStateFlow<List<CarNormEntity>>(emptyList())
    override suspend fun forCar(carId: Long) = rows.value.filter { it.carId == carId }
    override fun observeForCar(carId: Long): Flow<List<CarNormEntity>> = rows.map { l -> l.filter { it.carId == carId } }
    override suspend fun put(rows: List<CarNormEntity>) {
        val keys = rows.map { Triple(it.carId, it.key, it.source) }.toSet()
        this.rows.value = this.rows.value.filterNot { Triple(it.carId, it.key, it.source) in keys } + rows
    }
    override suspend fun delete(carId: Long, key: String, source: String) {
        rows.value = rows.value.filterNot { it.carId == carId && it.key == key && it.source == source }
    }
    override suspend fun deleteNorms(carId: Long, source: String) {
        rows.value = rows.value.filterNot { it.carId == carId && it.source == source && !it.key.startsWith("trait.") }
    }
}

/** Room repositories added in rounds 1-2 that had no direct test: trip events, health checks, car norms (fake DAOs). */
class RoomRepositoriesTest {
    @Test
    fun `trip events map known types, drop unknown ones and filter by car and time`() = runTest {
        val dao = MemEventDao()
        dao.insert(TripEventEntity(sessionId = 1, carId = 3, atMs = 2_000, type = TripEventType.USER_MARK.name))
        dao.insert(TripEventEntity(sessionId = 1, carId = 3, atMs = 1_000, type = TripEventType.STALL.name))
        dao.insert(TripEventEntity(sessionId = 1, carId = 3, atMs = 1_500, type = "FROM_A_NEWER_BUILD"))
        dao.insert(TripEventEntity(sessionId = 2, carId = 4, atMs = 1_200, type = TripEventType.STALL.name))
        val repo = RoomTripEventRepository(dao)
        assertEquals(listOf(TripEventType.STALL, TripEventType.USER_MARK), repo.forSession(1).map { it.type })
        assertEquals(listOf(1_000L, 2_000L), repo.observe(1).first().map { it.atMs })
        assertEquals(listOf(1_000L), repo.forCar(3, fromMs = 0, toMs = 1_200).map { it.atMs })
        assertEquals(TripEvent(4, 2, 1_200, TripEventType.STALL), repo.forCar(4).single())
        assertEquals(io.github.iampopovich.eculense.core.analytics.TripEventFact(2, 1_200, TripEventType.STALL), repo.forCar(4).single().toFact())
    }

    @Test
    fun `health checks round-trip items, codes and the verdict, an unknown verdict reads as not supported`() = runTest {
        val dao = MemHealthDao()
        val repo = RoomHealthRepository(dao)
        val items = listOf(
            HealthItem(HealthItemId.STORED_CODES, HealthStatus.FAULT, 1.0),
            HealthItem(HealthItemId.COOLANT, HealthStatus.OK, 90.0, HealthNote.NONE, Norm(NormKey.COOLANT_PLATEAU, 85.0, 97.0, NormSource.BASELINE, sampleCount = 7)),
        )
        val record = HealthCheckRecord(0, 1, 5_000, HealthResult(HealthStatus.FAULT, items), listOf(Dtc("P0133", DtcKind.STORED)), null, null, null, 120_000)
        val id = repo.save(record)
        val back = repo.byId(id)!!
        assertEquals(record.copy(id = id).result.verdict, back.result.verdict)
        assertEquals(listOf(HealthItemId.STORED_CODES, HealthItemId.COOLANT), back.result.items.map { it.id })
        assertEquals(NormSource.BASELINE, back.result.items[1].norm?.source)
        assertEquals(listOf("P0133"), back.dtcs.map { it.code })
        assertNull(back.freezeFrame)
        assertEquals(120_000L, back.odometerKm)
        assertEquals(listOf(id), repo.observe(1).first().map { it.id })
        assertTrue(repo.observe(2).first().isEmpty())

        dao.rows.value = dao.rows.value.map { it.copy(verdict = "SOMETHING_NEW") }
        assertEquals(HealthStatus.NOT_SUPPORTED, repo.byId(id)!!.result.verdict)
        assertNull(repo.byId(404))
    }

    @Test
    fun `car norms - the user band wins and resets, baselines are replaced, only ECU traits are stored`() = runTest {
        val dao = MemNormDao()
        val cars = FakeCars()
        val carId = cars.activeCarId()!!
        val repo = RoomCarNormsRepository(dao, cars, clock = { 9_000L })

        repo.saveBaselines(carId, listOf(
            Norm(NormKey.COOLANT_PLATEAU, 87.0, 97.0, NormSource.BASELINE, center = 92.0, mad = 1.0, sampleCount = 10),
            Norm(NormKey.COOLANT_PLATEAU, 80.0, 100.0, NormSource.USER), // not a baseline: ignored here
        ))
        assertEquals(NormSource.BASELINE, repo.norms(carId).effective(NormKey.COOLANT_PLATEAU)?.source)

        repo.setUser(carId, NormKey.COOLANT_PLATEAU, 85.0, 99.0)
        val view = repo.observe(carId).first()
        val row = view.rows.single { it.key == NormKey.COOLANT_PLATEAU }
        assertEquals(NormSource.USER, row.effective?.source)
        assertEquals(9_000L, row.user?.updatedAtMs)
        assertEquals(92.0, row.baseline?.center)

        repo.resetUser(carId, NormKey.COOLANT_PLATEAU)
        assertEquals(NormSource.BASELINE, repo.norms(carId).effective(NormKey.COOLANT_PLATEAU)?.source)
        repo.saveBaselines(carId, emptyList()) // the learner lost its sessions: the baseline disappears
        assertNull(repo.norms(carId).baseline(NormKey.COOLANT_PLATEAU))

        repo.saveTraits(carId, EngineTraits(fuel = Trait(FuelKind.DIESEL, NormSource.ECU, 1L), hasMaf = Trait(true, NormSource.USER)))
        assertEquals(1, dao.rows.value.size) // the USER-sourced trait is not stored by saveTraits
        assertTrue(repo.norms(carId).traits.isDiesel)
        repo.saveTraits(carId, EngineTraits()) // nothing known: nothing written
        assertEquals(1, dao.rows.value.size)
    }
}

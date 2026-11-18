package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcRepositoryTest {
    private val dao = FakeDtcDao()
    private var now = 1_000_000L
    private var activeCar: Long? = 7
    private val repository = RoomDtcRepository(dao, clock = { now }, activeCarId = { activeCar })
    private val codes = listOf(Dtc("P0133", DtcKind.STORED), Dtc("P0300", DtcKind.STORED), Dtc("P0171", DtcKind.PENDING))

    @Test
    fun `a live read is stored for the active car`() = runTest {
        assertTrue(repository.recordRead(codes))
        assertEquals(listOf("P0133", "P0300", "P0171"), dao.items.map { it.code })
        assertTrue(dao.items.all { it.carId == 7L && it.takenAtMs == now })
        assertEquals(listOf("STORED", "STORED", "PENDING"), dao.items.map { it.kind })
    }

    @Test
    fun `the same codes read again within the window are not duplicated`() = runTest {
        repository.recordRead(codes)
        now += DtcRepository.DEDUP_WINDOW_MS - 1
        assertFalse(repository.recordRead(codes.reversed()))
        assertEquals(3, dao.items.size)
    }

    @Test
    fun `the same codes after the window are a new event`() = runTest {
        repository.recordRead(codes)
        now += DtcRepository.DEDUP_WINDOW_MS + 1
        assertTrue(repository.recordRead(codes))
        assertEquals(6, dao.items.size)
    }

    @Test
    fun `a different set within the window is stored`() = runTest {
        repository.recordRead(codes)
        now += 1_000
        assertTrue(repository.recordRead(codes.take(2)))
        assertEquals(5, dao.items.size)
    }

    @Test
    fun `dedup is per car`() = runTest {
        repository.recordRead(codes)
        activeCar = 8
        now += 1_000
        assertTrue(repository.recordRead(codes))
        assertEquals(listOf(7L, 8L), dao.items.map { it.carId }.distinct())
    }

    @Test
    fun `an empty read stores nothing`() = runTest {
        assertFalse(repository.recordRead(emptyList()))
        assertTrue(dao.items.isEmpty())
    }
}

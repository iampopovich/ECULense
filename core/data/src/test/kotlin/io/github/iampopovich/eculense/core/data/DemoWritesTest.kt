package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.EndpointLabel
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import io.github.iampopovich.eculense.core.obd.Ignition
import io.github.iampopovich.eculense.core.obd.Readiness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Item 0 of batch 5: a demo or replayed link never writes into the active car's history. */
class DemoWritesTest {
    private val info = AdapterInfo("ELM327 v1.5", '6', true, 12.6)
    private val demo = AdapterEndpoint("demo", "virtual", "Demo", EndpointLabel.DEMO)
    private val replay = AdapterEndpoint("replay", "log", "Replay", EndpointLabel.REPLAY)
    private val real = AdapterEndpoint("bt", "00:00:00:00:00:01", "OBDII")
    private val codes = listOf(Dtc("P0133", DtcKind.STORED))
    private val readiness = Readiness(milOn = true, dtcCount = 1, ignition = Ignition.SPARK, monitors = emptyList())

    private fun setup(endpoint: AdapterEndpoint): Pair<FakeDtcDao, RoomDtcRepository> {
        val controller = FakeConnectionController(initial = ConnectionState.Connected(endpoint, info, emptySet()))
        val dao = FakeDtcDao()
        return dao to RoomDtcRepository(dao, clock = { 1_000L }, activeCarId = { 1L }, liveWrites = LiveWriteGate.of(controller))
    }

    @Test
    fun `demo reads and readiness are not stored`() = runTest {
        val (dao, repository) = setup(demo)
        assertFalse(repository.recordRead(codes, null, null))
        repository.saveReadiness(ReadinessReason.BEFORE_CLEAR, readiness, null)
        assertTrue(dao.items.isEmpty())
        assertTrue(dao.readiness.isEmpty())
    }

    @Test
    fun `a replayed log is not stored either`() = runTest {
        val (dao, repository) = setup(replay)
        assertFalse(repository.recordRead(codes))
        assertTrue(dao.items.isEmpty())
    }

    @Test
    fun `a real adapter is stored`() = runTest {
        val (dao, repository) = setup(real)
        assertTrue(repository.recordRead(codes))
        repository.saveReadiness(ReadinessReason.AFTER_CLEAR, readiness, null)
        assertEquals(1, dao.items.size)
        assertEquals(1, dao.readiness.size)
    }

    @Test
    fun `an import is not gated`() = runTest {
        val (dao, repository) = setup(demo)
        repository.saveSnapshot(codes, vin = null, takenAtMs = 5L, carId = 1L)
        assertEquals(1, dao.items.size)
    }
}

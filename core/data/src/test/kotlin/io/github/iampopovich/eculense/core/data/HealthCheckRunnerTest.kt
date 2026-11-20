package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.analytics.HealthItemId
import io.github.iampopovich.eculense.core.analytics.HealthNote
import io.github.iampopovich.eculense.core.analytics.HealthItem
import io.github.iampopovich.eculense.core.analytics.HealthStatus
import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.EndpointLabel
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import io.github.iampopovich.eculense.core.obd.FakeSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthCheckRunnerTest {
    private class MemHealth : HealthRepository {
        val saved = mutableListOf<HealthCheckRecord>()
        override fun observe(carId: Long?): Flow<List<HealthCheckRecord>> = flowOf(saved.toList())
        override suspend fun byId(id: Long) = saved.firstOrNull { it.id == id }
        override suspend fun save(record: HealthCheckRecord): Long = (saved.size + 1L).also { saved += record.copy(id = it) }
    }

    private val info = AdapterInfo("ELM327 v1.5", '6', true, 12.6)
    private val demo = AdapterEndpoint("demo", "virtual", "Demo", EndpointLabel.DEMO)
    private val real = AdapterEndpoint("bt", "00:00:00:00:00:01", "OBDII")

    private fun runner(endpoint: AdapterEndpoint, repo: HealthRepository): Pair<HealthCheckRunner, FakeConnectionController> {
        val session = FakeSession(values = mapOf(0x0C to 780.0, 0x05 to 90.0), dtcs = listOf(Dtc("P0133", DtcKind.STORED)))
        val controller = FakeConnectionController(initial = ConnectionState.Connected(endpoint, info, setOf(0x0C, 0x05)), session = session)
        return HealthCheckRunner(controller, repo, LiveWriteGate.of(controller), activeCarId = { 1L }, odometerKm = { 123_456L }, clock = { 5_000L }, liveMs = 2_000L) to controller
    }

    @Test
    fun `a real adapter check is judged and stored for the active car`() = runTest {
        val repo = MemHealth()
        val (r, controller) = runner(real, repo)
        val done = r.run() as HealthProgress.Done
        assertTrue(done.stored)
        assertEquals(1, repo.saved.size)
        assertEquals(1L, repo.saved[0].carId)
        assertEquals(123_456L, repo.saved[0].odometerKm)
        assertEquals(HealthStatus.FAULT, done.record.result.items.single { it.id == HealthItemId.STORED_CODES }.status)
        assertEquals(listOf("P0133"), done.record.dtcs.map { it.code })
        // The live owner is released afterwards.
        assertTrue(controller.polledBy.value[HealthCheckRunner.OWNER].isNullOrEmpty())
    }

    @Test
    fun `a demo check is shown but never stored`() = runTest {
        val repo = MemHealth()
        val done = runner(demo, repo).first.run() as HealthProgress.Done
        assertFalse(done.stored)
        assertEquals(0L, done.record.id)
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun `without a connection nothing runs`() = runTest {
        val repo = MemHealth()
        val r = HealthCheckRunner(FakeConnectionController(), repo, LiveWriteGate.ALWAYS, { 1L }, { null })
        assertEquals(HealthProgress.NotConnected, r.run())
    }

    @Test
    fun `items and codes survive the text columns`() {
        val items = listOf(
            HealthItem(HealthItemId.COOLANT, HealthStatus.OK, 112.0, HealthNote.RESIDUAL_HEAT),
            HealthItem(HealthItemId.O2_SENSOR, HealthStatus.NOT_SUPPORTED),
        )
        assertEquals(items, HealthCodec.decodeItems(HealthCodec.encodeItems(items)))
        val codes = listOf(Dtc("P0133", DtcKind.STORED), Dtc("P0171", DtcKind.PENDING))
        assertEquals(codes, HealthCodec.decodeDtcs(HealthCodec.encodeDtcs(codes)))
    }
}

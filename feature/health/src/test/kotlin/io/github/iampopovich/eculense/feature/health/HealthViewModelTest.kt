package io.github.iampopovich.eculense.feature.health

import io.github.iampopovich.eculense.core.analytics.HealthResult
import io.github.iampopovich.eculense.core.analytics.HealthStatus
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.HealthCheckRecord
import io.github.iampopovich.eculense.core.data.HealthProgress
import io.github.iampopovich.eculense.core.data.HealthRepository
import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class Cars(car: CarProfile?) : CarRepository {
    val active = MutableStateFlow(car)
    override fun observeCars() = active.map { listOfNotNull(it) }
    override fun observeActiveCar(): Flow<CarProfile?> = active
    override suspend fun activeCarId() = active.value?.id
    override suspend fun cars() = listOfNotNull(active.value)
    override suspend fun byExternalId(externalId: String) = null
    override suspend fun save(car: CarProfile) = car.id
    override suspend fun delete(id: Long) = false
    override suspend fun setActive(id: Long) = Unit
}

private class MemHealth(vararg records: HealthCheckRecord) : HealthRepository {
    val all = MutableStateFlow(records.toList())
    override fun observe(carId: Long?): Flow<List<HealthCheckRecord>> = all.map { l -> l.filter { it.carId == carId } }
    override suspend fun byId(id: Long) = all.value.firstOrNull { it.id == id }
    override suspend fun save(record: HealthCheckRecord): Long = record.id
}

@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val car = CarProfile(id = 1, name = "Тестовая", brand = "Kia", model = "Ceed", year = 2019)
    private val connected = ConnectionState.Connected(AdapterEndpoint("bt", "00:00:00:00:00:01", "OBDII"), AdapterInfo("ELM327 v1.5", '6', true, 12.6), setOf(0x0C))

    private fun record(id: Long, carId: Long? = 1, at: Long = 1_000L * id) =
        HealthCheckRecord(id, carId, at, HealthResult(HealthStatus.OK, emptyList()), emptyList(), null, null, null, null)

    private class Rig(val vm: HealthViewModel, val progress: MutableStateFlow<HealthProgress>, val runs: () -> Int, val gate: CompletableDeferred<Unit>)

    private fun rig(state: ConnectionState = connected, repo: HealthRepository = MemHealth(record(1), record(2), record(3, carId = 2)), openId: Long? = null, ai: Boolean = false): Rig {
        val progress = MutableStateFlow<HealthProgress>(HealthProgress.Idle)
        var runs = 0
        val gate = CompletableDeferred<Unit>()
        val vm = HealthViewModel(progress, { runs++; progress.value = HealthProgress.ReadingCodes; gate.await() }, repo, FakeConnectionController(initial = state), Cars(car), ai, openId)
        return Rig(vm, progress, { runs }, gate)
    }

    @Test
    fun `history is the active car's checks, connection and AI flags come through`() = runTest {
        val r = rig(ai = true)
        backgroundScope.launchCollect(r.vm)
        val s = r.vm.uiState.value
        assertTrue(s.connected)
        assertTrue(s.aiAvailable)
        assertEquals(listOf(1L, 2L), s.history.map { it.id })
        assertNull(s.shown)
        assertFalse(rig(state = ConnectionState.Disconnected).also { backgroundScope.launchCollect(it.vm) }.vm.uiState.value.connected)
    }

    @Test
    fun `run starts the check once while it runs, and the finished check is shown with its stored flag`() = runTest {
        val r = rig()
        backgroundScope.launchCollect(r.vm)
        r.vm.run()
        r.vm.run() // second tap while running is ignored
        assertEquals(1, r.runs())
        assertTrue(r.vm.uiState.value.running)
        r.progress.value = HealthProgress.Live(secondsLeft = 10)
        assertTrue(r.vm.uiState.value.running)
        r.progress.value = HealthProgress.Done(record(9), stored = false) // simulated link: shown, not kept
        r.gate.complete(Unit)
        val s = r.vm.uiState.value
        assertFalse(s.running)
        assertEquals(9L, s.shown?.id)
        assertFalse(s.shownStored)
        r.vm.run()
        assertEquals(2, r.runs())
    }

    @Test
    fun `a check opened from the history (or by deep link id) wins over the last run until the next run`() = runTest {
        val r = rig(openId = 2)
        backgroundScope.launchCollect(r.vm)
        assertEquals(2L, r.vm.uiState.value.shown?.id)
        assertTrue(r.vm.uiState.value.shownStored)
        r.progress.value = HealthProgress.Done(record(9), stored = true)
        assertEquals(2L, r.vm.uiState.value.shown?.id)
        r.vm.open(record(1))
        assertEquals(1L, r.vm.uiState.value.shown?.id)
        r.vm.run() // a new run clears the opened one
        assertNull(r.vm.uiState.value.shown)
        assertTrue(r.vm.uiState.value.running)
    }

    @Test
    fun `failure and not connected are passed through, unknown deep link id shows nothing`() = runTest {
        val r = rig(openId = 404)
        backgroundScope.launchCollect(r.vm)
        assertNull(r.vm.uiState.value.shown)
        r.progress.value = HealthProgress.NotConnected
        assertEquals(HealthProgress.NotConnected, r.vm.uiState.value.progress)
        r.progress.value = HealthProgress.Failed("test")
        assertFalse(r.vm.uiState.value.running)
        assertEquals(HealthProgress.Failed("test"), r.vm.uiState.value.progress)
    }
}

/** uiState is WhileSubscribed: keep one collector alive for the test. */
private fun kotlinx.coroutines.CoroutineScope.launchCollect(vm: HealthViewModel) {
    launch(UnconfinedTestDispatcher()) { vm.uiState.collect {} }
}

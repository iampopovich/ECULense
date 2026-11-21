package io.github.iampopovich.eculense.feature.sessions

import org.robolectric.RuntimeEnvironment
import android.content.Context
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.SessionMover
import io.github.iampopovich.eculense.core.data.SessionRepository
import io.github.iampopovich.eculense.core.data.TripEvent
import io.github.iampopovich.eculense.core.data.TripEventRepository
import io.github.iampopovich.eculense.core.data.UnitsRepository
import io.github.iampopovich.eculense.core.analytics.TripEventType
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.Session
import io.github.iampopovich.eculense.core.model.SessionSource
import io.github.iampopovich.eculense.core.model.UnitsSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

private class MemSessions(sessions: List<Session>, private val readings: Map<Long, List<SensorReading>>) : SessionRepository {
    val all = MutableStateFlow(sessions)
    var failReadings = false
    override fun observeSessions() = all
    override suspend fun start(name: String, source: SessionSource, vin: String?, startedAtMs: Long?, externalId: String?, carId: Long?) = 0L
    override suspend fun append(sessionId: Long, readings: List<SensorReading>) = Unit
    override suspend fun finish(sessionId: Long, endedAtMs: Long?) = Unit
    override suspend fun readings(sessionId: Long): List<SensorReading> {
        if (failReadings) throw IOException("database closed")
        return readings[sessionId].orEmpty()
    }
    override suspend fun delete(sessionId: Long) { all.value = all.value.filterNot { it.id == sessionId } }
    override suspend fun hasExternal(externalId: String) = false
    override suspend fun assignCar(externalId: String, carId: Long) = Unit
}

private class Units : UnitsRepository {
    override val units = MutableStateFlow(UnitsSettings())
    override suspend fun save(units: UnitsSettings) { this.units.value = units }
}

private class Mover(var car: Long?) : SessionMover {
    val moves = mutableListOf<Pair<Long, Long>>()
    var failCarOf = false
    override suspend fun carOf(sessionId: Long): Long? = if (failCarOf) error("db") else car
    override suspend fun move(sessionId: Long, carId: Long): Boolean {
        moves += sessionId to carId
        if (car == carId) return false
        car = carId
        return true
    }
}

private class TwoCars : CarRepository {
    val list = listOf(CarProfile(id = 1, name = "Тестовая"), CarProfile(id = 2, name = "Test car"))
    override fun observeCars(): Flow<List<CarProfile>> = flowOf(list)
    override fun observeActiveCar(): Flow<CarProfile?> = flowOf(list.first())
    override suspend fun activeCarId() = 1L
    override suspend fun cars() = list
    override suspend fun byExternalId(externalId: String) = null
    override suspend fun save(car: CarProfile) = car.id
    override suspend fun delete(id: Long) = false
    override suspend fun setActive(id: Long) = Unit
}

private class Events : TripEventRepository {
    override fun observe(sessionId: Long) = flowOf(listOf(TripEvent(1, sessionId, 2_000L, TripEventType.STALL)))
    override suspend fun forSession(sessionId: Long) = emptyList<TripEvent>()
    override suspend fun forCar(carId: Long, fromMs: Long, toMs: Long) = emptyList<TripEvent>()
}

/** Recordings list and page (Robolectric: Context, FileProvider, SystemClock). Synthetic readings only. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionsViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private val sessions = listOf(Session(7, "Test 1", 1_000L, 9_000L, SessionSource.LIVE), Session(8, "Test 2", 10_000L, null, SessionSource.LIVE))
    private val readings = mapOf(
        7L to (0 until 50).flatMap { i ->
            listOf(
                SensorReading("01.05", 80.0 + i % 3, "°C", 1_000L + i * 10_000L),
                SensorReading("01.0C", 800.0 + i, "rpm", 1_000L + i * 10_000L + 10),
            )
        },
    )

    private fun await(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(what, condition())
    }

    private fun detail(repo: MemSessions = MemSessions(sessions, readings), mover: SessionMover? = Mover(1), id: Long = 7) =
        SessionDetailViewModel(id, repo, context, Units(), Events(), mover, TwoCars()).also { vm ->
            scope.launch(Dispatchers.Unconfined) { vm.events.collect {} }
            scope.launch(Dispatchers.Unconfined) { vm.cars.collect {} }
        }

    @Test
    fun `the list follows the repository and delete removes a recording`() {
        val repo = MemSessions(sessions, readings)
        val vm = SessionsViewModel(repo)
        scope.launch(Dispatchers.Unconfined) { vm.sessions.collect {} }
        await("loaded") { vm.sessions.value.size == 2 }
        vm.delete(8)
        await("deleted") { vm.sessions.value.map { it.id } == listOf(7L) }
    }

    @Test
    fun `the page picks rpm by default, streams a decimated chart, and an event focus toggles`() {
        val vm = detail()
        await("chart") { vm.series.value != null && !vm.loading.value }
        assertEquals(listOf("01.05", "01.0C"), vm.sensors.value)
        assertEquals("01.0C", vm.selected.value)
        assertEquals(1_000L, vm.startMs.value)
        assertEquals(1, vm.events.value.size)
        assertEquals(2, vm.cars.value.size)
        assertEquals(1L, vm.carId.value)
        val whole = vm.series.value!!.sourceCount
        assertEquals(50, whole)

        vm.select("01.05")
        await("coolant") { vm.selected.value == "01.05" && !vm.loading.value }
        vm.toggleFocus(2_000L)
        assertEquals(2_000L, vm.focus.value)
        await("window") { !vm.loading.value && (vm.series.value?.sourceCount ?: whole) < whole }
        vm.toggleFocus(2_000L) // the same event again = whole recording
        assertNull(vm.focus.value)
        await("whole again") { !vm.loading.value && vm.series.value?.sourceCount == whole }
    }

    @Test
    fun `an empty recording stops loading without a sensor`() {
        val vm = detail(id = 8)
        await("done") { !vm.loading.value }
        assertTrue(vm.sensors.value.isEmpty())
        assertNull(vm.selected.value)
        assertNull(vm.series.value)
    }

    @Test
    fun `moveTo moves to another car once, the same car is a no-op, no mover means no move`() {
        val mover = Mover(1)
        val vm = detail(mover = mover)
        await("car") { vm.carId.value == 1L }
        vm.moveTo(2)
        await("moved") { vm.carId.value == 2L }
        vm.moveTo(2)
        assertEquals(listOf(7L to 2L, 7L to 2L), mover.moves)
        assertEquals(2L, vm.carId.value)

        val none = detail(mover = null)
        none.moveTo(2)
        Thread.sleep(50)
        assertNull(none.carId.value)
        val broken = Mover(1).apply { failCarOf = true }
        val unknown = detail(mover = broken)
        Thread.sleep(100)
        assertNull(unknown.carId.value) // a failing lookup shows no car instead of crashing
    }

    @Test
    fun `CSV export writes every reading into a fresh cache file and shares it through the FileProvider`() = runBlocking {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val old = File(dir, "ecu-session-6.csv").apply { writeText("old") }
        val vm = detail()
        val uri = vm.exportCsv()
        assertEquals("content", uri.scheme)
        assertTrue(uri.toString(), uri.toString().endsWith("ecu-session-7.csv"))
        val file = File(dir, "ecu-session-7.csv")
        val lines = file.readLines()
        assertEquals(101, lines.size) // header + 100 readings
        assertFalse("previous exports are deleted", old.exists())
    }

    @Test
    fun `CSV export failure is thrown to the caller (the screen shows an error), nothing is shared`() = runBlocking {
        val repo = MemSessions(sessions, readings)
        val vm = detail(repo = repo)
        await("loaded") { !vm.loading.value }
        repo.failReadings = true
        try {
            vm.exportCsv()
            fail("export must fail")
        } catch (e: IOException) {
            assertEquals("database closed", e.message)
        }
    }

    @Test
    fun `engine map builds this recording and the comparison, a failing read gives an empty map`() {
        val repo = MemSessions(sessions, readings)
        val vm = EngineMapViewModel(7, 8, repo)
        await("built") { !vm.state.value.loading }
        assertEquals(7L, vm.state.value.session?.id)
        assertEquals(8L, vm.state.value.otherSession?.id)
        assertTrue(vm.state.value.other != null)
        repo.failReadings = true
        val failing = EngineMapViewModel(7, null, repo)
        await("built anyway") { !failing.state.value.loading }
        assertTrue(failing.state.value.map!!.points.isEmpty())
        assertNull(failing.state.value.other)
    }
}

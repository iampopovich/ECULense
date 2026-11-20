package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.analytics.AlertThreshold
import io.github.iampopovich.eculense.core.analytics.LiveAlertConfig
import io.github.iampopovich.eculense.core.analytics.LiveAlertKind
import io.github.iampopovich.eculense.core.analytics.TripEventType
import io.github.iampopovich.eculense.core.database.EventDao
import io.github.iampopovich.eculense.core.database.SessionMoveDao
import io.github.iampopovich.eculense.core.database.TripEventEntity
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.EndpointLabel
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import io.github.iampopovich.eculense.core.obd.FakeSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingNotifier : TripAlertNotifier {
    val alerts = mutableListOf<LiveAlertKind>()
    val cleared = mutableListOf<LiveAlertKind>()
    val codes = mutableListOf<String>()
    override fun liveAlert(kind: LiveAlertKind, value: Double) { alerts += kind }
    override fun clearLiveAlert(kind: LiveAlertKind) { cleared += kind }
    override fun newCode(code: String) { codes += code }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LiveAlertMonitorTest {
    private val connected = FakeConnectionController.connected(setOf(0x05, 0x0C, 0x0D, 0x42))

    private fun TestScope.monitor(controller: FakeConnectionController, notifier: RecordingNotifier, config: LiveAlertConfig = LiveAlertConfig()) =
        LiveAlertMonitor(controller, InMemoryAlertPrefs(config), backgroundScope, { notifier }, clock = { testScheduler.currentTime }).also {
            it.start(); runCurrent()
        }

    @Test
    fun `overheat raises a banner and one notification, polls its pids, clears on disconnect`() = runTest {
        val controller = FakeConnectionController(initial = connected, session = FakeSession())
        val notifier = RecordingNotifier()
        val m = monitor(controller, notifier)
        assertEquals(listOf(0x05, 0x0C, 0x42), controller.polledBy.value[LiveAlertMonitor.OWNER]?.map { it.code })
        repeat(8) {
            controller.readingsFlow.emit(SensorReading("01.05", 112.0, "°C", 0))
            advanceTimeBy(1_000); runCurrent()
        }
        assertEquals(listOf(LiveAlertKind.OVERHEAT), m.active.value.map { it.kind })
        assertEquals(listOf(LiveAlertKind.OVERHEAT), notifier.alerts)
        controller.disconnect(); runCurrent()
        assertTrue(m.active.value.isEmpty())
        assertEquals(listOf(LiveAlertKind.OVERHEAT), notifier.cleared)
        assertTrue(LiveAlertMonitor.OWNER !in controller.polledBy.value)
    }

    @Test
    fun `codes present at connect do not alert, a new code read 60 s later does`() = runTest {
        val session = FakeSession(dtcs = listOf(Dtc("P0133", DtcKind.STORED)))
        val controller = FakeConnectionController(initial = connected, session = session)
        val notifier = RecordingNotifier()
        val m = monitor(controller, notifier)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(m.newCodes.value.isEmpty())
        session.dtcs = listOf(Dtc("P0133", DtcKind.STORED), Dtc("P0171", DtcKind.PENDING))
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf("P0171"), m.newCodes.value)
        assertEquals(listOf("P0171"), notifier.codes)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf("P0171"), notifier.codes)
        m.dismissNewCodes()
        assertTrue(m.newCodes.value.isEmpty())
    }

    @Test
    fun `switched off alerts poll nothing extra and read no codes`() = runTest {
        val off = AlertThreshold(false, 0.0)
        val session = FakeSession(dtcs = emptyList())
        val controller = FakeConnectionController(initial = connected, session = session)
        val notifier = RecordingNotifier()
        monitor(controller, notifier, LiveAlertConfig(off, off, off, newDtc = false))
        assertTrue(LiveAlertMonitor.OWNER !in controller.polledBy.value)
        session.dtcs = listOf(Dtc("P0300", DtcKind.STORED))
        advanceTimeBy(200_000); runCurrent()
        assertTrue(notifier.codes.isEmpty())
    }
}

private class FakeEventDao : EventDao {
    val items = mutableListOf<TripEventEntity>()
    override suspend fun insert(event: TripEventEntity): Long { items += event.copy(id = items.size + 1L); return items.size.toLong() }
    override suspend fun forSession(sessionId: Long) = items.filter { it.sessionId == sessionId }
    override fun observeForSession(sessionId: Long) = flowOf(items.filter { it.sessionId == sessionId })
    override suspend fun forCar(carId: Long, fromMs: Long, toMs: Long) = items.filter { it.carId == carId && it.atMs in fromMs..toMs }
}

private class FakeRecorderForEvents(id: Long?) : SessionRecorder {
    override val activeSessionId = MutableStateFlow(id)
    override fun start(name: String, carId: Long?, auto: Boolean) = Unit
    override fun stop() { activeSessionId.value = null }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LiveEventRecorderTest {
    private val real = FakeConnectionController.connected(setOf(0x0C, 0x0D))

    private fun TestScope.setup(state: ConnectionState, recording: Long?): Triple<FakeConnectionController, FakeEventDao, LiveEventRecorder> {
        val controller = FakeConnectionController(initial = state)
        val dao = FakeEventDao()
        val r = LiveEventRecorder(controller, FakeRecorderForEvents(recording), dao, LiveWriteGate.of(controller), backgroundScope, carOf = { 3L }, clock = { 42L })
        r.start(); runCurrent()
        return Triple(controller, dao, r)
    }

    @Test
    fun `a stall during a recording is stored with the session and car`() = runTest {
        val (controller, dao, _) = setup(real, 9L)
        controller.readingsFlow.emit(SensorReading("01.0D", 20.0, "km/h", 1_000))
        controller.readingsFlow.emit(SensorReading("01.0C", 900.0, "rpm", 1_000))
        controller.readingsFlow.emit(SensorReading("01.0C", 0.0, "rpm", 3_000))
        runCurrent()
        assertEquals(listOf(TripEventEntity(1, 9, 3, 3_000, "STALL")), dao.items)
    }

    @Test
    fun `a mark is stored only while recording a real car`() = runTest {
        val (_, dao, r) = setup(real, 9L)
        assertEquals(MarkResult.SAVED, r.mark())
        assertEquals(TripEventType.USER_MARK.name, dao.items.single().type)
        val (_, _, idle) = setup(real, null)
        assertEquals(MarkResult.NOT_RECORDING, idle.mark())
    }

    @Test
    fun `demo stalls and marks are not stored`() = runTest {
        val demo = real.copy(endpoint = AdapterEndpoint("demo-engine-off", "v", "Demo", EndpointLabel.DEMO_ENGINE_OFF))
        val (controller, dao, r) = setup(demo, 9L)
        controller.readingsFlow.emit(SensorReading("01.0C", 900.0, "rpm", 1_000))
        controller.readingsFlow.emit(SensorReading("01.0C", 0.0, "rpm", 2_000))
        runCurrent()
        assertEquals(MarkResult.SIMULATED, r.mark())
        assertTrue(dao.items.isEmpty())
    }
}

private class FakeMoveDao(var car: Long?) : SessionMoveDao {
    val moves = mutableListOf<Pair<Long, Long>>()
    override suspend fun carOf(sessionId: Long) = car
    override suspend fun moveSession(sessionId: Long, carId: Long) { car = carId }
    override suspend fun moveTrips(sessionId: Long, carId: Long) = 1
    override suspend fun moveEvents(sessionId: Long, carId: Long) = Unit
    override suspend fun move(sessionId: Long, carId: Long) { moves += sessionId to carId; super.move(sessionId, carId) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionMoverTest {
    @Test
    fun `moving a recording recomputes both cars, moving to the same car does nothing`() = runTest {
        val dao = FakeMoveDao(car = 1)
        val recomputed = mutableListOf<Long>()
        val mover = RoomSessionMover(dao, backgroundScope) { recomputed += it }
        assertTrue(mover.move(5, 2))
        runCurrent()
        assertEquals(listOf(5L to 2L), dao.moves)
        assertEquals(listOf(1L, 2L), recomputed)
        assertEquals(false, mover.move(5, 2))
        assertEquals(1, dao.moves.size)
    }
}

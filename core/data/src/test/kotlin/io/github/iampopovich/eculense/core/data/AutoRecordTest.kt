package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.EndpointLabel
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRecordMachineTest {
    private val m = AutoRecordMachine()

    private fun speed(v: Double, t: Long) = m.onReading("01.0D", v, t, hasSpeedPid = true)

    @Test
    fun `starts after 5 s above 10 km per h`() {
        assertNull(speed(20.0, 0))
        assertNull(speed(25.0, 4_999))
        assertEquals(AutoRecordMachine.Action.START, speed(30.0, 5_000))
        assertEquals(AutoRecordMachine.State.Recording, m.state)
    }

    @Test
    fun `slowing down resets the arming`() {
        speed(20.0, 0)
        speed(8.0, 3_000)
        assertNull(speed(20.0, 5_000))
        assertEquals(AutoRecordMachine.Action.START, speed(20.0, 10_000))
    }

    @Test
    fun `without a speed pid rpm above zero arms`() {
        assertNull(m.onReading("01.0C", 800.0, 0, hasSpeedPid = false))
        assertNull(m.onReading("01.0D", 50.0, 6_000, hasSpeedPid = false))
        assertEquals(AutoRecordMachine.Action.START, m.onReading("01.0C", 820.0, 6_000, hasSpeedPid = false))
    }

    @Test
    fun `stops 3 min after engine off, a shorter stop continues`() {
        speed(20.0, 0); speed(20.0, 5_000)
        m.onLinkDown(10_000)
        assertNull(m.onTick(10_000 + AutoRecordMachine.HOLD_MS - 1))
        m.onLinkUp()
        assertEquals(AutoRecordMachine.State.Recording, m.state)
        m.onLinkDown(400_000)
        assertEquals(AutoRecordMachine.Action.STOP, m.onTick(400_000 + AutoRecordMachine.HOLD_MS))
        assertEquals(AutoRecordMachine.State.Idle, m.state)
    }

    @Test
    fun `a manual recording is left alone and a manual stop does not re-arm until the link goes down`() {
        m.onManualStart()
        assertNull(speed(50.0, 0)); assertNull(speed(50.0, 10_000))
        m.onLinkDown(20_000)
        speed(20.0, 30_000)
        assertEquals(AutoRecordMachine.Action.START, speed(20.0, 35_000))
        m.onRecordingEnded()
        assertNull(speed(20.0, 50_000)); assertNull(speed(20.0, 60_000))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AutoRecorderTest {
    private val supported = setOf(0x05, 0x0C, 0x0D)
    private val real = FakeConnectionController.connected(supported)

    private fun TestScope.setup(state: ConnectionState, autoRecord: Boolean = true): Triple<FakeConnectionController, LiveSessionRecorder, FakeSessionRepository> {
        val controller = FakeConnectionController(initial = state)
        val repository = FakeSessionRepository()
        val recorder = LiveSessionRecorder(controller, repository, backgroundScope)
        val prefs = InMemoryAutomationPrefs(AutomationSettings(autoRecord = autoRecord))
        AutoRecorder(controller, recorder, prefs, backgroundScope, { "Auto $it" }, clock = { testScheduler.currentTime }).start()
        runCurrent()
        return Triple(controller, recorder, repository)
    }

    private suspend fun FakeConnectionController.drive(kmh: Double, seconds: Int, scope: TestScope) {
        repeat(seconds) {
            readingsFlow.emit(SensorReading("01.0D", kmh, "km/h", scope.testScheduler.currentTime))
            scope.advanceTimeBy(1_000)
            scope.runCurrent()
        }
    }

    @Test
    fun `drives, records, holds 3 min after engine off and stops`() = runTest {
        val (controller, recorder, repository) = setup(real)
        assertEquals(listOf(0x0C, 0x0D), controller.polledBy.value[AutoRecorder.OWNER]?.map { it.code })
        controller.drive(30.0, 7, this)
        assertEquals(1L, recorder.activeSessionId.value)
        assertTrue(recorder.activeIsAuto.value)

        controller.stateFlow.value = ConnectionState.EngineOff(real.endpoint, 12.4)
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        controller.stateFlow.value = ConnectionState.EngineOff(real.endpoint, 12.3)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1L, recorder.activeSessionId.value)
        advanceTimeBy(61_000); runCurrent()
        assertNull(recorder.activeSessionId.value)
        assertEquals(listOf(1L), repository.finished)
    }

    @Test
    fun `a short stop continues the same recording`() = runTest {
        val (controller, recorder, repository) = setup(real)
        controller.drive(30.0, 7, this)
        controller.stateFlow.value = ConnectionState.EngineOff(real.endpoint, 12.4)
        runCurrent()
        advanceTimeBy(90_000); runCurrent()
        controller.stateFlow.value = real
        runCurrent()
        advanceTimeBy(200_000); runCurrent()
        assertEquals(1L, recorder.activeSessionId.value)
        assertTrue(repository.finished.isEmpty())
    }

    @Test
    fun `a demo link never starts a recording`() = runTest {
        val demo = real.copy(endpoint = AdapterEndpoint("demo", "virtual", "Demo", EndpointLabel.DEMO))
        val (controller, recorder, _) = setup(demo)
        controller.drive(60.0, 10, this)
        assertNull(recorder.activeSessionId.value)
    }

    @Test
    fun `setting off does nothing`() = runTest {
        val (controller, recorder, _) = setup(real, autoRecord = false)
        controller.drive(60.0, 10, this)
        assertNull(recorder.activeSessionId.value)
        assertFalse(AutoRecorder.OWNER in controller.polledBy.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AutoConnectTest {
    private val real = FakeConnectionController.connected(setOf(0x0C))

    @Test
    fun `remembers a real adapter and reconnects to it on app start when enabled`() = runTest {
        val controller = FakeConnectionController(initial = real)
        val prefs = InMemoryAutomationPrefs(AutomationSettings(autoConnect = true))
        val auto = AutoConnect(controller, prefs, backgroundScope)
        auto.start(); runCurrent()
        controller.disconnect(); runCurrent()
        auto.onAppStart(); runCurrent()
        auto.onAppStart(); runCurrent()
        assertEquals(listOf(real.endpoint), controller.connectCalls)
    }

    @Test
    fun `demo is not remembered and auto connect is off by default`() = runTest {
        val demo = real.copy(endpoint = AdapterEndpoint("demo", "virtual", "Demo", EndpointLabel.DEMO))
        val controller = FakeConnectionController(initial = demo)
        val prefs = InMemoryAutomationPrefs()
        val auto = AutoConnect(controller, prefs, backgroundScope)
        auto.start(); runCurrent()
        assertNull(prefs.lastAdapter.value())
        controller.disconnect(); runCurrent()
        auto.onAppStart(); runCurrent()
        assertTrue(controller.connectCalls.isEmpty())
    }

    @Test
    fun `endpoint encoding round-trips`() {
        val e = AdapterEndpoint("bt", "00:00:00:00:00:01", "OBDII", null)
        assertEquals(e, DataStoreAutomationPrefs.decodeEndpoint(DataStoreAutomationPrefs.encodeEndpoint(e)))
        assertNull(DataStoreAutomationPrefs.decodeEndpoint("garbage"))
    }

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.value(): T = first()
}

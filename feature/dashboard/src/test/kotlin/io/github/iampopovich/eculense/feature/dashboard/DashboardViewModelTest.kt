package io.github.iampopovich.eculense.feature.dashboard

import io.github.iampopovich.eculense.core.data.InMemoryDashboardPrefs
import io.github.iampopovich.eculense.core.data.SessionRecorder
import io.github.iampopovich.eculense.core.data.UnitsRepository
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.UnitsSettings
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import io.github.iampopovich.eculense.core.obd.ObdError
import io.github.iampopovich.eculense.core.obdtext.R as ObdTextR
import io.github.iampopovich.eculense.core.ui.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeRecorder : SessionRecorder {
    override val activeSessionId = MutableStateFlow<Long?>(null)
    val names = mutableListOf<String>()
    override fun start(name: String) { names += name; activeSessionId.value = 7L }
    override fun stop() { activeSessionId.value = null }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private var clock = 1_000_000L
    private val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    private fun TestScope.vm(
        controller: FakeConnectionController,
        prefs: InMemoryDashboardPrefs = InMemoryDashboardPrefs(),
        units: FakeUnits = FakeUnits(),
        recorder: FakeRecorder = FakeRecorder(),
    ): DashboardViewModel {
        val vm = DashboardViewModel(controller, recorder, units, prefs, now = { clock }, ticks = ticks.onStart { emit(Unit) })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private fun DashboardViewModel.tile(code: Int) = uiState.value.tiles.first { it.pid.code == code }

    @Test
    fun `polls supported default pids and shows latest values`() = runTest {
        val controller = FakeConnectionController(initial = FakeConnectionController.connected(setOf(0x0C, 0x0D, 0x99)))
        val vm = vm(controller)
        assertEquals(listOf(0x0C, 0x0D), controller.polled.value.map { it.code })
        controller.readingsFlow.emit(SensorReading("01.0C", 800.0, "rpm", 0))
        assertEquals(800.0, vm.tile(0x0C).value)
        assertEquals(ValueStatus.LIVE, vm.tile(0x0C).status)
        assertEquals(ValueStatus.NO_DATA, vm.tile(0x0D).status)
        assertEquals(null, vm.tile(0x0D).value)
    }

    @Test
    fun `user selection per car decides the tiles and their order`() = runTest {
        val controller = FakeConnectionController(initial = FakeConnectionController.connected(setOf(0x0C, 0x0D, 0x05, 0x2F)))
        val prefs = InMemoryDashboardPrefs(selection = listOf(0x2F, 0x0C, 0x46))
        val vm = vm(controller, prefs)
        // 0x46 is not supported by this car: hidden, not shown as an empty tile.
        assertEquals(listOf(0x2F, 0x0C), vm.uiState.value.tiles.map { it.pid.code })
        assertEquals(listOf(0x2F, 0x0C), controller.polled.value.map { it.code })
        prefs.selection.value = listOf(0x0D)
        assertEquals(listOf(0x0D), controller.polled.value.map { it.code })
    }

    @Test
    fun `fuel trims add the fuel system status to polling and show open loop`() = runTest {
        val controller = FakeConnectionController(initial = FakeConnectionController.connected(setOf(0x03, 0x06, 0x0C)))
        val vm = vm(controller, InMemoryDashboardPrefs(selection = listOf(0x0C, 0x06)))
        assertEquals(listOf(0x0C, 0x06, 0x03), controller.polled.value.map { it.code })
        controller.readingsFlow.emit(SensorReading("01.03", 1.0, "", 0))
        clock += 300
        controller.readingsFlow.emit(SensorReading("01.06", 3.1, "%", 0))
        assertEquals(ValueStatus.OPEN_LOOP, vm.tile(0x06).status)
        clock += 300
        controller.readingsFlow.emit(SensorReading("01.03", 2.0, "", 0))
        assertEquals(ValueStatus.LIVE, vm.tile(0x06).status)
    }

    @Test
    fun `disabled pid is shown as not answering, never as zero`() = runTest {
        val controller = FakeConnectionController(initial = FakeConnectionController.connected(setOf(0x0C, 0x05)))
        val vm = vm(controller, InMemoryDashboardPrefs(selection = listOf(0x0C, 0x05)))
        controller.readingsFlow.emit(SensorReading("01.05", 90.0, "°C", 0))
        controller.disabledPids.value = setOf(0x05)
        assertEquals(ValueStatus.NOT_ANSWERING, vm.tile(0x05).status)
        assertEquals(null, vm.tile(0x05).value)
    }

    @Test
    fun `old values are dimmed with their age after three tier periods`() = runTest {
        val controller = FakeConnectionController(initial = FakeConnectionController.connected(setOf(0x0C, 0x05)))
        controller.pollStats.value = io.github.iampopovich.eculense.core.obd.PollStats(10, cycleMsP50 = 500, pidMsP50 = emptyMap())
        val vm = vm(controller, InMemoryDashboardPrefs(selection = listOf(0x0C, 0x05)))
        controller.readingsFlow.emit(SensorReading("01.0C", 800.0, "rpm", 0))
        controller.readingsFlow.emit(SensorReading("01.05", 90.0, "°C", 0))
        clock += 4_000
        ticks.emit(Unit)
        // rpm (FAST): stale after max(3 s, 3 × 1 × 500 ms) = 3 s; coolant (SLOW): 3 × 10 × 500 ms = 15 s.
        assertEquals(ValueStatus.STALE, vm.tile(0x0C).status)
        assertEquals(4L, vm.tile(0x0C).ageSec)
        assertEquals(ValueStatus.LIVE, vm.tile(0x05).status)
        assertEquals(800.0, vm.tile(0x0C).value)
    }

    @Test
    fun `engine off keeps the values on screen, dimmed under the banner`() = runTest {
        val connected = FakeConnectionController.connected(setOf(0x0C, 0x0D))
        val controller = FakeConnectionController(initial = connected)
        val vm = vm(controller)
        controller.readingsFlow.emit(SensorReading("01.0C", 800.0, "rpm", 0))
        controller.stateFlow.value = ConnectionState.Reconnecting(connected.endpoint, 2, 2_000, ObdError.AdapterClosed)
        assertTrue(vm.uiState.value.connected)
        assertEquals(UiText.Res(ObdTextR.string.obd_state_reconnecting, listOf(2, 2)), vm.uiState.value.banner)
        assertEquals(800.0, vm.tile(0x0C).value)
        assertEquals(ValueStatus.STALE, vm.tile(0x0C).status)
        assertEquals(listOf(0x0C, 0x0D), controller.polled.value.map { it.code })
        controller.stateFlow.value = ConnectionState.EngineOff(connected.endpoint, 12.3)
        assertEquals(UiText.Res(ObdTextR.string.obd_state_engine_off_voltage, listOf(12.3)), vm.uiState.value.banner)
        assertEquals(ValueStatus.STALE, vm.tile(0x0C).status)
        controller.stateFlow.value = connected
        assertEquals(null, vm.uiState.value.banner)
        assertEquals(ValueStatus.LIVE, vm.tile(0x0C).status)
    }

    @Test
    fun `fuel rate is estimated from MAF when the car does not report it`() = runTest {
        val controller = FakeConnectionController(initial = FakeConnectionController.connected(setOf(0x0C, 0x10)))
        val vm = vm(controller, InMemoryDashboardPrefs(selection = listOf(0x0C, 0x5E)))
        // 5E itself is not polled (unsupported), MAF is.
        assertEquals(listOf(0x0C, 0x10), controller.polled.value.map { it.code })
        controller.readingsFlow.emit(SensorReading("01.10", 10.0, "g/s", 0))
        val fuel = vm.tile(0x5E)
        assertTrue(fuel.estimated)
        assertEquals(3.29, fuel.value!!, 0.01)
    }

    @Test
    fun `sparkline keeps the last 60 s`() = runTest {
        val controller = FakeConnectionController(initial = FakeConnectionController.connected(setOf(0x0C)))
        val vm = vm(controller)
        repeat(90) { i ->
            controller.readingsFlow.emit(SensorReading("01.0C", 800.0 + i, "rpm", 0))
            clock += 1_000
        }
        ticks.emit(Unit)
        val spark = vm.tile(0x0C).spark
        assertTrue("${spark.size}", spark.size in 55..61)
        assertEquals(889f, spark.last())
    }

    @Test
    fun `toggle recording`() = runTest {
        val recorder = FakeRecorder()
        val vm = vm(FakeConnectionController(initial = FakeConnectionController.connected(setOf(0x0C))), recorder = recorder)
        vm.toggleRecording { "Trip $it" }
        assertTrue(vm.uiState.value.recording)
        assertTrue(recorder.names.single(), recorder.names.single().matches(Regex("Trip \\d{2}\\.\\d{2} \\d{2}:\\d{2}")))
    }

    @Test
    fun `connected state shows a compact adapter status line`() = runTest {
        val controller = FakeConnectionController(initial = FakeConnectionController.connected(setOf(0x0C)))
        val vm = vm(controller)
        assertEquals(UiText.Res(R.string.dash_status_voltage, listOf(UiText.Raw("Fake ELM"), '6', 12.6)), vm.uiState.value.status)
        controller.readingsFlow.emit(SensorReading("elm.voltage", 14.1, "V", 0))
        assertEquals(UiText.Res(R.string.dash_status_voltage, listOf(UiText.Raw("Fake ELM"), '6', 14.1)), vm.uiState.value.status)
        controller.disconnect()
        assertEquals(null, vm.uiState.value.status)
    }

    @Test
    fun `values are shown in the user's units`() = runTest {
        val controller = FakeConnectionController(initial = FakeConnectionController.connected(setOf(0x0D)))
        val units = FakeUnits()
        val vm = vm(controller, units = units)
        controller.readingsFlow.emit(SensorReading("01.0D", 100.0, "km/h", 0))
        assertEquals("km/h", vm.uiState.value.tiles.single().unit)
        units.units.value = UnitsSettings(metricDistance = false)
        val speed = vm.uiState.value.tiles.single()
        assertEquals("mph", speed.unit)
        assertEquals(62.14, speed.value!!, 0.01)
    }

    @Test
    fun `driving grid is wider in landscape`() {
        assertEquals(1 to 2, drivingGrid(2, landscape = false))
        assertEquals(2 to 2, drivingGrid(4, landscape = false))
        assertEquals(2 to 3, drivingGrid(6, landscape = false))
        assertEquals(2 to 1, drivingGrid(2, landscape = true))
        assertEquals(3 to 1, drivingGrid(3, landscape = true))
        assertEquals(3 to 2, drivingGrid(6, landscape = true))
    }
}

private class FakeUnits : UnitsRepository {
    override val units = MutableStateFlow(UnitsSettings())
    override suspend fun save(units: UnitsSettings) {
        this.units.value = units
    }
}

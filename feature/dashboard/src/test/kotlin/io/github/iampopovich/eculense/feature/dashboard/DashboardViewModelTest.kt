package io.github.iampopovich.eculense.feature.dashboard

import io.github.iampopovich.eculense.core.data.SessionRecorder
import io.github.iampopovich.eculense.core.data.UnitsRepository
import io.github.iampopovich.eculense.core.model.UnitsSettings
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import io.github.iampopovich.eculense.core.ui.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

    @Test
    fun `polls supported default pids and shows latest values`() = runTest {
        val controller = FakeConnectionController(initial = FakeConnectionController.connected(setOf(0x0C, 0x0D, 0x99)))
        val vm = DashboardViewModel(controller, FakeRecorder(), FakeUnits())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertEquals(listOf(0x0C, 0x0D), controller.polled.value.map { it.code })
        controller.readingsFlow.emit(SensorReading("01.0C", 800.0, "rpm", 0))
        assertEquals(800.0, vm.uiState.value.values.first { it.pid.code == 0x0C }.value)
    }

    @Test
    fun `toggle recording`() = runTest {
        val recorder = FakeRecorder()
        val vm = DashboardViewModel(FakeConnectionController(initial = FakeConnectionController.connected(setOf(0x0C))), recorder, FakeUnits())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.toggleRecording { "Trip $it" }
        assertTrue(vm.uiState.value.recording)
        assertTrue(recorder.names.single(), recorder.names.single().matches(Regex("Trip \\d{2}\\.\\d{2} \\d{2}:\\d{2}")))
    }

    @Test
    fun `connected state shows a compact adapter status line`() = runTest {
        val controller = FakeConnectionController(initial = FakeConnectionController.connected(setOf(0x0C)))
        val vm = DashboardViewModel(controller, FakeRecorder(), FakeUnits())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(UiText.Res(R.string.dash_status_voltage, listOf("Fake ELM", '6', 12.6)), vm.uiState.value.status)
        controller.disconnect()
        assertEquals(null, vm.uiState.value.status)
    }

    @Test
    fun `values are shown in the user's units`() = runTest {
        val controller = FakeConnectionController(initial = FakeConnectionController.connected(setOf(0x0D)))
        val units = FakeUnits()
        val vm = DashboardViewModel(controller, FakeRecorder(), units)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        controller.readingsFlow.emit(SensorReading("01.0D", 100.0, "km/h", 0))
        assertEquals("km/h", vm.uiState.value.values.single().unit)
        units.units.value = UnitsSettings(metricDistance = false)
        val speed = vm.uiState.value.values.single()
        assertEquals("mph", speed.unit)
        assertEquals(62.14, speed.value!!, 0.01)
    }
}

private class FakeUnits : UnitsRepository {
    override val units = MutableStateFlow(UnitsSettings())
    override suspend fun save(units: UnitsSettings) {
        this.units.value = units
    }
}

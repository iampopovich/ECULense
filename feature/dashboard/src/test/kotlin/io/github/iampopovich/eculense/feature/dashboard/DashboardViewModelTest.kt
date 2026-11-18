package io.github.iampopovich.eculense.feature.dashboard

import io.github.iampopovich.eculense.core.data.SessionRecorder
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
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
    override fun start(name: String) { activeSessionId.value = 7L }
    override fun stop() { activeSessionId.value = null }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `polls supported default pids and shows latest values`() = runTest {
        val controller = FakeConnectionController(initial = FakeConnectionController.connected(setOf(0x0C, 0x0D, 0x99)))
        val vm = DashboardViewModel(controller, FakeRecorder())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertEquals(listOf(0x0C, 0x0D), controller.polled.value.map { it.code })
        controller.readingsFlow.emit(SensorReading("01.0C", 800.0, "rpm", 0))
        assertEquals(800.0, vm.uiState.value.values.first { it.pid.code == 0x0C }.value)
    }

    @Test
    fun `toggle recording`() = runTest {
        val recorder = FakeRecorder()
        val vm = DashboardViewModel(FakeConnectionController(initial = FakeConnectionController.connected(setOf(0x0C))), recorder)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.toggleRecording()
        assertTrue(vm.uiState.value.recording)
    }
}

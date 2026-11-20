package io.github.iampopovich.eculense.feature.cardata

import io.github.iampopovich.eculense.core.data.InMemoryDashboardPrefs
import io.github.iampopovich.eculense.core.data.UnitsRepository
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.UnitsSettings
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import io.github.iampopovich.eculense.core.obd.PidSystem
import io.github.iampopovich.eculense.core.obd.StandardPids
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

@OptIn(ExperimentalCoroutinesApi::class)
class CarDataViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val supported = setOf(0x03, 0x05, 0x06, 0x0C, 0x0D, 0x10, 0x14, 0x42)

    private class Units : UnitsRepository {
        override val units = MutableStateFlow(UnitsSettings())
        override suspend fun save(units: UnitsSettings) { this.units.value = units }
    }

    @Test
    fun `supported pids are grouped by system, unknown ones collapsed as unsupported, all polled while open`() = runTest {
        val controller = FakeConnectionController(initial = FakeConnectionController.connected(supported))
        val vm = CarDataViewModel(controller, InMemoryDashboardPrefs(), Units())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        val state = vm.uiState.value
        assertEquals(listOf(PidSystem.ENGINE, PidSystem.FUEL, PidSystem.AIR, PidSystem.EXHAUST, PidSystem.ELECTRICAL, PidSystem.OTHER), state.groups.map { it.system })
        assertEquals(listOf(0x05, 0x0C), state.groups.first { it.system == PidSystem.ENGINE }.rows.map { it.pid.code })
        // Fuel rate is not reported but can be estimated from MAF.
        val fuel = state.groups.first { it.system == PidSystem.FUEL }.rows
        assertEquals(listOf(0x03, 0x06, 0x5E), fuel.map { it.pid.code })
        assertTrue(fuel.last().estimated)
        assertTrue(state.unsupported.none { it.code in supported || it.code == 0x5E })
        assertTrue(state.unsupported.any { it.code == 0x2F })
        assertEquals(StandardPids.all.filter { it.code in supported }.map { it.code }, controller.polledBy.value.getValue("cardata").map { it.code })

        controller.readingsFlow.emit(SensorReading("01.05", 88.0, "°C", 0))
        assertEquals(88.0, vm.uiState.value.groups.first().rows.first { it.pid.code == 0x05 }.value)
        vm.onClearedForTest()
        assertTrue("cardata" !in controller.polledBy.value)
    }

    @Test
    fun `star adds and removes dashboard pids, arrows reorder, starting from the defaults`() = runTest {
        val controller = FakeConnectionController(initial = FakeConnectionController.connected(supported))
        val prefs = InMemoryDashboardPrefs()
        val vm = CarDataViewModel(controller, prefs, Units())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        // Defaults limited to the car: rpm, speed, coolant, voltage, MAF.
        assertEquals(listOf(0x0C, 0x0D, 0x05, 0x42, 0x10), vm.uiState.value.panel.map { it.code })
        vm.togglePanel(0x14)
        vm.togglePanel(0x0D)
        assertEquals(listOf(0x0C, 0x05, 0x42, 0x10, 0x14), prefs.selection.value)
        vm.moveUp(0x14)
        vm.moveDown(0x0C)
        assertEquals(listOf(0x05, 0x0C, 0x42, 0x14, 0x10), prefs.selection.value)
        assertTrue(vm.uiState.value.groups.flatMap { it.rows }.first { it.pid.code == 0x14 }.onPanel)
    }
}

package io.github.iampopovich.eculense.feature.cardata

import io.github.iampopovich.eculense.core.data.ExtendedSensors
import io.github.iampopovich.eculense.core.data.ExtendedSensorsPrefs
import io.github.iampopovich.eculense.core.data.ExtendedStatus
import io.github.iampopovich.eculense.core.data.InMemoryDashboardPrefs
import io.github.iampopovich.eculense.core.demo.ObdbFixtures
import io.github.iampopovich.eculense.core.obdb.SignalsetParser
import io.github.iampopovich.eculense.core.obdb.SimulatedSignalset
import io.github.iampopovich.eculense.core.obdb.StoredSignalset
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertFalse
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

    @Test
    fun `extended OBDb sensors - off by default, switched on they are polled, listed with values and fill the EV card`() = runTest {
        val connected = FakeConnectionController.connected(supported).let { it.copy(endpoint = it.endpoint.copy(kind = "demo-niro-ev")) }
        val controller = FakeConnectionController(initial = connected)
        val niro = StoredSignalset("OBDb/Kia-Niro-EV", "test", SignalsetParser.parse(ObdbFixtures.signalset(ObdbFixtures.NIRO_EV)))
        val sim = object : SimulatedSignalset {
            override val endpointKind = "demo-niro-ev"
            override val year = 2020
            override fun signalset() = niro
        }
        val prefs = object : ExtendedSensorsPrefs {
            override val enabled = MutableStateFlow(false)
            override suspend fun setEnabled(on: Boolean) { enabled.value = on }
        }
        val sensors = ExtendedSensors(controller, MutableStateFlow(null), { _, _ -> null }, prefs, listOf(sim), backgroundScope)
        sensors.start()
        val vm = CarDataViewModel(controller, InMemoryDashboardPrefs(), Units(), sensors)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        sensors.status.first { it is ExtendedStatus.Ready }
        assertFalse(vm.uiState.value.extended.enabled)
        assertTrue(controller.extendedBy.value.isEmpty())
        vm.setExtended(true)
        controller.extendedBy.first { it.isNotEmpty() }
        mapOf("NIROEV_HVBAT_SOC" to 24.0, "NIROEV_HVBAT_VDC" to 344.5, "NIROEV_HVBAT_CURR" to 5.9, "NIROEV_C_V_MAX" to 3.5, "NIROEV_C_V_MIN" to 3.48,
            "NIROEV_HVBAT_T_MAX" to 3.0, "NIROEV_HVBAT_T_MIN" to 2.0, "NIROEV_HVBAT_SOH" to 100.0).forEach { (id, v) ->
            controller.readingsFlow.emit(SensorReading("obdb:$id", v, "", 0))
        }
        val ext = vm.uiState.value.extended
        assertTrue(ext.enabled)
        assertEquals(24.0, ext.rows.first { it.signal.sensorId == "obdb:NIROEV_HVBAT_SOC" }.value!!, 1e-9)
        assertTrue(ext.waiting > 0)
        val ev = ext.ev!!
        assertEquals(24.0, ev.socPct!!, 1e-9)
        assertEquals(100.0, ev.sohPct!!, 1e-9)
        assertEquals(344.5 * 5.9 / 1000, ev.powerKw!!, 1e-9)
        assertEquals(0.02, ev.cellSpreadV!!, 1e-9)
        assertEquals(2.0, ev.tempMinC!!, 1e-9)
    }
}

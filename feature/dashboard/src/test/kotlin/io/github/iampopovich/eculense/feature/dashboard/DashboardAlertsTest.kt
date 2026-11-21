package io.github.iampopovich.eculense.feature.dashboard

import io.github.iampopovich.eculense.core.analytics.LiveAlertKind
import io.github.iampopovich.eculense.core.data.ActiveAlert
import io.github.iampopovich.eculense.core.data.AutomationSettings
import io.github.iampopovich.eculense.core.data.EventMarker
import io.github.iampopovich.eculense.core.data.InMemoryAlertPrefs
import io.github.iampopovich.eculense.core.data.InMemoryAutomationPrefs
import io.github.iampopovich.eculense.core.data.InMemoryDashboardPrefs
import io.github.iampopovich.eculense.core.data.LiveAlerts
import io.github.iampopovich.eculense.core.data.MarkResult
import io.github.iampopovich.eculense.core.data.SessionRecorder
import io.github.iampopovich.eculense.core.analytics.LiveAlertConfig
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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

internal class FakeCars(n: Int) : io.github.iampopovich.eculense.core.data.CarRepository {
    private val list = (1..n).map { io.github.iampopovich.eculense.core.model.CarProfile(id = it.toLong(), name = "Test car $it") }
    override fun observeCars() = flowOf(list)
    override fun observeActiveCar() = flowOf(list.firstOrNull())
    override suspend fun activeCarId() = list.firstOrNull()?.id
    override suspend fun cars() = list
    override suspend fun byExternalId(externalId: String) = null
    override suspend fun save(car: io.github.iampopovich.eculense.core.model.CarProfile) = car.id
    override suspend fun delete(id: Long) = false
    override suspend fun setActive(id: Long) = Unit
}

private class FakeAlerts : LiveAlerts {
    override val active = MutableStateFlow<List<ActiveAlert>>(emptyList())
    override val newCodes = MutableStateFlow<List<String>>(emptyList())
    override fun dismissNewCodes() { newCodes.value = emptyList() }
}

private class Rec : SessionRecorder {
    override val activeSessionId = MutableStateFlow<Long?>(null)
    val starts = mutableListOf<Long?>()
    override fun start(name: String, carId: Long?, auto: Boolean) { starts += carId; activeSessionId.value = 1 }
    override fun stop() { activeSessionId.value = null }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardAlertsTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(alerts: FakeAlerts = FakeAlerts(), rec: Rec = Rec(), cars: FakeCars? = null, auto: InMemoryAutomationPrefs? = null, marker: EventMarker? = null): DashboardViewModel {
        val controller = FakeConnectionController(initial = FakeConnectionController.connected(setOf(0x05, 0x0C, 0x42)))
        val vm = DashboardViewModel(
            controller, rec, FakeUnits(), InMemoryDashboardPrefs(selection = listOf(0x05, 0x0C, 0x42)),
            ticks = flowOf(Unit), alerts = alerts, marker = marker, cars = cars, automation = auto,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun `an overheat alert reaches the banner and colors only the coolant tile`() = runTest {
        val alerts = FakeAlerts()
        val vm = vm(alerts)
        alerts.active.value = listOf(ActiveAlert(LiveAlertKind.OVERHEAT, 112.0))
        val s = vm.uiState.value
        assertEquals(LiveAlertKind.OVERHEAT, s.alerts.single().kind)
        assertTrue(s.tiles.first { it.pid?.code == 0x05 }.alert)
        assertFalse(s.tiles.first { it.pid?.code == 0x42 }.alert)
        alerts.newCodes.value = listOf("P0171")
        assertEquals(listOf("P0171"), vm.uiState.value.newCodes)
        vm.dismissNewCodes()
        assertTrue(vm.uiState.value.newCodes.isEmpty())
    }

    @Test
    fun `with two cars the car sheet opens, the pick starts the recording for that car and can stop asking`() = runTest {
        val rec = Rec()
        val auto = InMemoryAutomationPrefs()
        val vm = vm(rec = rec, cars = FakeCars(2), auto = auto)
        vm.toggleRecording()
        assertEquals(2, vm.uiState.value.carChoice?.size)
        assertTrue(rec.starts.isEmpty())
        vm.startRecordingFor(2L, dontAskAgain = true)
        assertEquals(listOf<Long?>(2L), rec.starts)
        assertNull(vm.uiState.value.carChoice)
        assertFalse(auto.settings.first().askCar)
    }

    @Test
    fun `one car or ask off starts at once on the active car`() = runTest {
        val rec = Rec()
        vm(rec = rec, cars = FakeCars(1)).toggleRecording()
        assertEquals(listOf<Long?>(null), rec.starts)
        val rec2 = Rec()
        vm(rec = rec2, cars = FakeCars(3), auto = InMemoryAutomationPrefs(AutomationSettings(askCar = false))).toggleRecording()
        assertEquals(listOf<Long?>(null), rec2.starts)
    }

    @Test
    fun `mark result is shown once`() = runTest {
        val vm = vm(marker = EventMarker { MarkResult.SAVED })
        vm.markEvent()
        assertEquals(MarkResult.SAVED, vm.uiState.value.markResult)
        vm.markShown()
        assertNull(vm.uiState.value.markResult)
    }

    @Test
    fun `alert thresholds parse both decimal marks and reject out of range numbers`() = runTest {
        assertEquals(11.5, parseThreshold(LiveAlertKind.NOT_CHARGING, "11,5")!!, 0.0)
        assertNull(parseThreshold(LiveAlertKind.OVERHEAT, "400"))
        assertNull(parseThreshold(LiveAlertKind.OVERCHARGE, "abc"))
        val prefs = InMemoryAlertPrefs()
        val vm = AlertSettingsViewModel(prefs, saveScope = backgroundScope, debounceMs = 600)
        vm.setText(LiveAlertKind.OVERHEAT, "60")
        // Typed numbers wait for a quiet period, switches are stored at once (and keep the stored threshold).
        assertEquals(LiveAlertConfig().overheat.value, prefs.config.first().overheat.value, 0.0)
        testScheduler.advanceTimeBy(700)
        testScheduler.runCurrent()
        assertEquals(60.0, prefs.config.first().overheat.value, 0.0)
        vm.setEnabled(LiveAlertKind.OVERCHARGE, false)
        testScheduler.runCurrent()
        val c = prefs.config.first()
        assertEquals(60.0, c.overheat.value, 0.0)
        assertFalse(c.overcharge.enabled)
        assertTrue(vm.state.value.saved)
        // An invalid number is never stored: the last valid value stays until the text is fixed.
        vm.setText(LiveAlertKind.NOT_CHARGING, "abc")
        testScheduler.advanceTimeBy(700)
        testScheduler.runCurrent()
        assertEquals(LiveAlertConfig().notCharging.value, prefs.config.first().notCharging.value, 0.0)
        assertFalse(vm.state.value.saved)
        vm.resetDefaults()
        testScheduler.runCurrent()
        assertEquals(LiveAlertConfig(), prefs.config.first())
    }
}

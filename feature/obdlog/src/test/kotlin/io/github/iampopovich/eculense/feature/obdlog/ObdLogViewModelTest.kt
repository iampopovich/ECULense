package io.github.iampopovich.eculense.feature.obdlog

import io.github.iampopovich.eculense.core.obd.RingTrafficLog
import io.github.iampopovich.eculense.core.obd.TrafficDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObdLogViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakePrefs : TrafficLogPrefs {
        val flow = MutableStateFlow(true)
        override val recording = flow
        override suspend fun setRecording(enabled: Boolean) {
            flow.value = enabled
        }
    }

    @Test
    fun `entries come newest first and clear empties them`() = runTest {
        val log = RingTrafficLog(clock = { 0L })
        val vm = ObdLogViewModel(log, FakePrefs(), files = null, refreshMs = 0)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.entries.collect {} }
        log.record(TrafficDirection.TX, "010C\r".encodeToByteArray())
        log.record(TrafficDirection.RX, "410C1AF8\r\r>".encodeToByteArray(), 40)
        val entries = vm.entries.first { it.size == 2 }
        assertEquals(listOf(TrafficDirection.RX, TrafficDirection.TX), entries.map { it.direction })
        vm.clear()
        assertEquals(0, vm.entries.first { it.isEmpty() }.size)
    }

    @Test
    fun `switch turns recording off and is saved`() = runTest {
        val log = RingTrafficLog(clock = { 0L })
        val prefs = FakePrefs()
        val vm = ObdLogViewModel(log, prefs, files = null)
        vm.setRecording(false)
        advanceUntilIdle()
        assertFalse(log.enabled.value)
        assertFalse(prefs.flow.value)
        log.note("ignored")
        assertEquals(0, log.snapshot().size)
    }

    @Test
    fun `saved switch is applied to the log at start`() = runTest {
        val log = RingTrafficLog(clock = { 0L })
        val prefs = FakePrefs().apply { flow.value = false }
        TrafficLogPrefsApplier(prefs, log, backgroundScope)
        runCurrent()
        assertFalse(log.enabled.value)
    }
}

package io.github.iampopovich.eculense.feature.connect

import io.github.iampopovich.eculense.core.obd.AdaptiveTiming
import io.github.iampopovich.eculense.core.obd.AdapterSettings
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import io.github.iampopovich.eculense.core.obd.InMemoryAdapterPrefs
import io.github.iampopovich.eculense.core.obd.InitCommandProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdapterSettingsViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads, validates and saves the adapter settings`() = runTest {
        val prefs = InMemoryAdapterPrefs(AdapterSettings(protocol = '5'))
        val vm = AdapterSettingsViewModel(prefs, FakeConnectionController(initial = FakeConnectionController.connected(emptySet())))
        assertEquals('5', vm.state.value.protocol)
        assertEquals('6', vm.detectedProtocol.value)

        vm.setExtraInit("ATST32\nATZ")
        assertEquals(listOf(InitCommandProblem.Forbidden("ATZ")), vm.state.value.problems)
        vm.save()
        assertFalse(vm.state.value.saved)

        vm.setExtraInit("ATST32")
        vm.setProtocol(null)
        vm.setTiming(AdaptiveTiming.AGGRESSIVE)
        vm.save()
        assertTrue(vm.state.value.saved)
        assertEquals(AdapterSettings(null, listOf("ATST32"), AdaptiveTiming.AGGRESSIVE), prefs.settings.first())
    }
}

package io.github.iampopovich.eculense.feature.dtc

import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import io.github.iampopovich.eculense.core.obd.FakeSession
import io.github.iampopovich.eculense.core.obd.MidGroup
import io.github.iampopovich.eculense.core.obd.ObdSession
import io.github.iampopovich.eculense.core.obdtext.ObdText
import io.github.iampopovich.eculense.core.ui.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.io.IOException

/** B8 on-board test results (mode 06): CAN only, grouped by monitor, errors as typed texts. Synthetic frames. */
@OptIn(ExperimentalCoroutinesApi::class)
class Mode06ViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val endpoint = AdapterEndpoint("bt", "00:00:00:00:00:01", "OBDII")
    private fun connected(can: Boolean) = ConnectionState.Connected(endpoint, AdapterInfo("ELM327 v1.5", if (can) '6' else '5', can, 12.6), setOf(0x0C))

    // MID 01 (O2 sensor) and, through the 0x20 continuation bit, MID 21 (catalyst); one test each.
    private val replies = mapOf(
        "0600" to "46 00 80 00 00 01",
        "0620" to "46 20 80 00 00 00",
        "0601" to "46 01 01 0A 00 64 00 00 03 E8",
        "0621" to "46 21 01 0A 00 32 00 00 03 E8",
    )

    @Test
    fun `not connected is an error, K-Line explains mode 06 is not read`() = runTest {
        val vm = Mode06ViewModel(FakeConnectionController(initial = ConnectionState.Disconnected))
        vm.read()
        assertEquals(UiText.Res(R.string.dtc_err_no_connection), vm.uiState.value.error)
        val kline = Mode06ViewModel(FakeConnectionController(initial = connected(can = false), session = FakeSession(replies = replies)))
        kline.read()
        assertTrue(kline.uiState.value.notCan)
        assertFalse(kline.uiState.value.checked)
    }

    @Test
    fun `results are grouped by monitor in a stable order`() = runTest {
        val vm = Mode06ViewModel(FakeConnectionController(initial = connected(can = true), session = FakeSession(replies = replies)))
        vm.read()
        val s = vm.uiState.value
        assertFalse(s.loading)
        assertTrue(s.checked)
        assertEquals(listOf(MidGroup.OXYGEN_SENSOR, MidGroup.CATALYST), s.groups.map { it.first })
        assertEquals(listOf(0x01, 0x21), s.groups.map { it.second.single().mid })
    }

    @Test
    fun `an ECU without mode 06 is checked with nothing to show, a lost session is an error`() = runTest {
        val empty = Mode06ViewModel(FakeConnectionController(initial = connected(can = true), session = FakeSession()))
        empty.read()
        assertTrue(empty.uiState.value.checked)
        assertTrue(empty.uiState.value.groups.isEmpty())

        val gone = Mode06ViewModel(FakeConnectionController(initial = connected(can = true), session = null))
        gone.read()
        assertEquals(UiText.Res(R.string.dtc_err_no_connection), gone.uiState.value.error)
        assertFalse(gone.uiState.value.loading)
    }

    @Test
    fun `a link failure while reading shows the adapter error and stops loading`() = runTest {
        val broken = object : ObdSession by FakeSession(replies = replies) {
            override suspend fun request(command: String, timeoutMs: Long): List<ByteArray>? = throw IOException("socket closed")
        }
        val vm = Mode06ViewModel(FakeConnectionController(initial = connected(can = true), session = broken))
        vm.read()
        assertEquals(ObdText.error(IOException("socket closed")), vm.uiState.value.error)
        assertFalse(vm.uiState.value.loading)
        assertFalse(vm.uiState.value.checked)
    }
}

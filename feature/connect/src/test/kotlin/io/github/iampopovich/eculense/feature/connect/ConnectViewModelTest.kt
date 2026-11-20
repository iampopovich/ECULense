package io.github.iampopovich.eculense.feature.connect

import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.EndpointLabel
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import io.github.iampopovich.eculense.core.obd.FakeSession
import io.github.iampopovich.eculense.core.obd.VehicleInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val obd = AdapterEndpoint("bt", "AA:BB", "OBDII")

    @Test
    fun `refresh loads endpoints`() = runTest {
        val vm = ConnectViewModel(FakeConnectionController(endpoints = listOf(obd)))
        vm.refresh()
        assertEquals(listOf(obd), vm.endpoints.value)
    }

    @Test
    fun `vehicle info is read once connected, a silent K-Line ECU gives no VIN`() = runTest {
        val vinHex = "1ECULENSE0TEST001".encodeToByteArray().joinToString("") { "%02X".format(it) }
        val can = FakeSession(replies = mapOf("0902" to "4902 01 $vinHex", "0906" to "49 06 01 A1 B2 C3 D4"))
        val controller = FakeConnectionController(session = can)
        val vm = ConnectViewModel(controller)
        assertEquals(null, vm.vehicleInfo.value)
        controller.stateFlow.value = FakeConnectionController.connected(setOf(0x0C))
        assertEquals(VehicleInfo(vin = "1ECULENSE0TEST001", cvns = listOf("A1B2C3D4")), vm.vehicleInfo.value)

        controller.stateFlow.value = ConnectionState.Disconnected
        assertEquals(null, vm.vehicleInfo.value)
        controller.session = FakeSession(isCanBus = false)
        controller.stateFlow.value = FakeConnectionController.connected(setOf(0x0C)).copy(endpoint = AdapterEndpoint("bt", "CC", "K"))
        assertEquals(VehicleInfo(), vm.vehicleInfo.value)
    }

    @Test
    fun `paired bluetooth first, demo adapter last`() = runTest {
        val demo = AdapterEndpoint("demo", "demo", "Demo mode (simulation)", EndpointLabel.DEMO)
        val wifi = AdapterEndpoint("wifi", "192.168.0.10:35000", "Wi-Fi ELM327")
        val vm = ConnectViewModel(FakeConnectionController(endpoints = listOf(demo, wifi, obd)))
        vm.refresh()
        assertEquals(listOf(obd, wifi, demo), vm.endpoints.value)
    }

    @Test
    fun `connect is delegated to controller`() = runTest {
        val controller = FakeConnectionController()
        ConnectViewModel(controller).connect(obd)
        assertEquals(listOf(obd), controller.connectCalls)
    }
}

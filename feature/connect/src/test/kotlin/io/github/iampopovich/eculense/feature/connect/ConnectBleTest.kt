package io.github.iampopovich.eculense.feature.connect

import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.AdapterScanner
import io.github.iampopovich.eculense.core.obd.BleScanRules
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import io.github.iampopovich.eculense.core.obd.GattUuids
import io.github.iampopovich.eculense.core.obd.ScanStart
import io.github.iampopovich.eculense.core.obd.ScannedAdapter
import io.github.iampopovich.eculense.core.obd.ScannedDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectBleTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeScanner(var result: ScanStart = ScanStart.STARTED) : AdapterScanner {
        override val kind = "ble"
        override val found = MutableStateFlow<List<ScannedAdapter>>(emptyList())
        override val scanning = MutableStateFlow(false)
        var starts = 0
        var stops = 0
        override fun start(durationMs: Long): ScanStart {
            starts++
            if (result == ScanStart.STARTED) scanning.value = true
            return result
        }
        override fun stop() {
            stops++
            scanning.value = false
        }
    }

    private val devices = listOf(
        ScannedDevice("11:11", "VIECAR", listOf(GattUuids.uuid16(0xFFF0)), -60),
        ScannedDevice("22:22", "Speaker", emptyList(), -40),
    )

    @Test
    fun `no scanner means no BLE section`() = runTest {
        assertNull(ConnectViewModel(FakeConnectionController()).ble.value)
    }

    @Test
    fun `adapters are listed, other devices only with show all, dual-mode adapters are flagged`() = runTest {
        val scanner = FakeScanner()
        val classic = AdapterEndpoint("bt", "AA:AA", "VIECAR")
        val vm = ConnectViewModel(FakeConnectionController(endpoints = listOf(classic)), scanner = scanner)
        vm.refresh()
        vm.startScan()
        assertTrue(vm.ble.value!!.scanning)
        scanner.found.value = BleScanRules.toAdapters("ble", devices)
        val section = vm.ble.value!!
        assertEquals(listOf("11:11"), section.rows.map { it.endpoint.address })
        assertEquals(1, section.hidden)
        assertTrue(section.rows.single().alsoClassic)
        vm.setShowAll(true)
        assertEquals(listOf("11:11", "22:22"), vm.ble.value!!.rows.map { it.endpoint.address })
        vm.stopScan()
        assertEquals(1, scanner.stops)
        assertEquals(false, vm.ble.value!!.scanning)
    }

    @Test
    fun `a scan that cannot start reports why`() = runTest {
        val scanner = FakeScanner(ScanStart.NO_PERMISSION)
        val vm = ConnectViewModel(FakeConnectionController(), scanner = scanner)
        vm.startScan()
        assertEquals(ScanStart.NO_PERMISSION, vm.ble.value!!.lastStart)
        assertEquals(false, vm.ble.value!!.scanning)
    }
}

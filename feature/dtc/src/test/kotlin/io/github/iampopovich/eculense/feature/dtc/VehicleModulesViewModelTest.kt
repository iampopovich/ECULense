package io.github.iampopovich.eculense.feature.dtc

import io.github.iampopovich.eculense.core.data.ModuleScanRepository
import io.github.iampopovich.eculense.core.data.StoredModule
import io.github.iampopovich.eculense.core.data.StoredModuleScan
import io.github.iampopovich.eculense.core.dtcdb.DtcInfo
import io.github.iampopovich.eculense.core.dtcdb.DtcSource
import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.obd.EcuSafetyPolicy
import io.github.iampopovich.eculense.core.obd.EcuTarget
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import io.github.iampopovich.eculense.core.obd.HeaderFormat
import io.github.iampopovich.eculense.core.obd.HeaderedChannel
import io.github.iampopovich.eculense.core.obd.HeaderedReply
import io.github.iampopovich.eculense.core.obd.HeaderedResponseParser
import io.github.iampopovich.eculense.core.obd.ModuleDtcRead
import io.github.iampopovich.eculense.core.obd.ModuleDtcService
import io.github.iampopovich.eculense.core.obd.ModuleKind
import io.github.iampopovich.eculense.core.obd.ModuleScanResult
import io.github.iampopovich.eculense.core.obd.ObdSession
import io.github.iampopovich.eculense.core.obd.Pid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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

/** R-D: the «Vehicle modules» view model over a scripted headered channel. */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleModulesViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    /** CAN replies by «header|command» (headers on, ATS0 shape). */
    private class ScriptedChannel(private val replies: Map<String, String>) : HeaderedChannel {
        val requests = mutableListOf<String>()
        override val protocol = '6'
        override val format = HeaderFormat.CAN_11
        private fun parse(key: String): HeaderedReply? {
            requests += key
            return replies[key]?.let { HeaderedResponseParser.parse(it.split('\r'), format) }
        }
        override suspend fun functional(command: String, timeoutMs: Long) = parse("7DF|$command")
        override suspend fun physical(target: EcuTarget, command: String, timeoutMs: Long): List<ByteArray>? {
            EcuSafetyPolicy().require(target, command)
            val a = target.address!!
            return parse("${a.tx}|$command")?.byEcu?.get(a.rx)
        }
    }

    private class ChannelSession(val channel: ScriptedChannel) : ObdSession {
        var scans = 0
        override suspend fun initialize() = AdapterInfo("fake", '6', true, 12.5)
        override suspend fun supportedPids() = emptySet<Int>()
        override suspend fun read(pid: Pid): SensorReading? = null
        override suspend fun readDtcs(kind: DtcKind) = emptyList<Dtc>()
        override suspend fun clearDtcs() = error("never clears")
        override suspend fun readVin(): String? = null
        override suspend fun <T> withHeaders(policy: EcuSafetyPolicy, block: suspend (HeaderedChannel) -> T): T {
            scans++
            return block(channel)
        }
    }

    private class FakeScans(var stored: StoredModuleScan? = null) : ModuleScanRepository {
        val saved = mutableListOf<ModuleScanResult>()
        override suspend fun save(result: ModuleScanResult): Boolean {
            saved += result
            return true
        }
        override suspend fun latest(carId: Long?) = stored
    }

    private val replies = mapOf(
        "7DF|0100" to "7E8064100BE3FA813\r7E906410080000001",
        "7E0|19020D" to "7E8037F1911",
        "7E0|03" to "7E806430101330000",
        "7E1|19020D" to "7E9075902FF07150009",
        "7D1|3E00" to "7D9027E00",
        "7D1|19020D" to "7D9100B5902FF403500\r7D9210940400008AAAA",
    )

    private val describer = DtcDescriber { codes -> codes.associateWith { DtcInfo(it, "title $it", DtcSource.BUILTIN) } }

    private fun connected() = FakeConnectionController(initial = FakeConnectionController.connected(setOf(0x0C, 0x0D)))

    @Test
    fun `a stationary car is scanned, rows show modules and counts, a module opens and closes`() = runTest {
        val session = ChannelSession(ScriptedChannel(replies))
        val controller = connected().apply { this.session = session }
        val scans = FakeScans()
        var t = 1_000L
        val vm = VehicleModulesViewModel(controller, describer, scans, now = { t }, ticks = flowOf(Unit))
        assertEquals(listOf(io.github.iampopovich.eculense.core.obd.StandardPids.byCode(0x0D)), controller.polledBy.value[VehicleModulesViewModel.OWNER])
        assertFalse(vm.uiState.value.canScan) // no speed reading yet
        controller.readingsFlow.emit(SensorReading("01.0D", 0.0, "km/h", t))
        assertTrue(vm.uiState.value.canScan)

        vm.scan()
        val s = vm.uiState.value
        assertFalse(s.scanning)
        assertEquals(listOf(ModuleKind.ENGINE, ModuleKind.TRANSMISSION, ModuleKind.ABS_ESC), s.rows.map { it.kind })
        assertEquals(listOf(1, 1, 2), s.rows.map { it.dtcCount })
        assertEquals(listOf(true, true, false), s.rows.map { it.emission })
        assertEquals("title C0040", s.rows[2].items[1].info.title)
        assertEquals(1, scans.saved.size)
        // The airbag module was never addressed.
        assertTrue(session.channel.requests.none { it.startsWith("7D2") || it.startsWith("7D4") })

        vm.open(2)
        assertEquals(2, vm.uiState.value.selected)
        vm.close()
        assertNull(vm.uiState.value.selected)
        vm.onClearedForTest()
        assertNull(controller.polledBy.value[VehicleModulesViewModel.OWNER])
    }

    @Test
    fun `a moving car is not scanned`() = runTest {
        val session = ChannelSession(ScriptedChannel(replies))
        val controller = connected().apply { this.session = session }
        val vm = VehicleModulesViewModel(controller, describer, FakeScans(), now = { 1_000L }, ticks = flowOf(Unit))
        controller.readingsFlow.emit(SensorReading("01.0D", 40.0, "km/h", 1_000L))
        assertEquals(SpeedGate.MOVING, vm.uiState.value.speed)
        vm.scan()
        assertEquals(0, session.scans)
        assertEquals(R.string.modules_stop_car, (vm.uiState.value.error as io.github.iampopovich.eculense.core.ui.UiText.Res).id)
    }

    @Test
    fun `a refused tap and a started scan are logged with the gate state, the disabled reason is always set`() = runTest {
        val session = ChannelSession(ScriptedChannel(replies))
        val controller = connected().apply { this.session = session }
        val logger = io.github.iampopovich.eculense.core.model.RecordingLogger()
        val vm = VehicleModulesViewModel(controller, describer, FakeScans(), now = { 1_000L }, ticks = flowOf(Unit), logger = logger)
        assertEquals(ScanBlock.WAITING_SPEED, vm.uiState.value.scanBlock)
        vm.scan()
        assertEquals(0, session.scans)
        assertTrue(logger.entries.single().message.startsWith("scan refused: WAITING_SPEED"))
        controller.readingsFlow.emit(SensorReading("01.0D", 0.0, "km/h", 1_000L))
        assertNull(vm.uiState.value.scanBlock)
        vm.scan()
        assertEquals(1, session.scans)
        assertTrue(logger.entries.any { it.tag == "EcuObd" && it.message.startsWith("scan started") })
        assertEquals(ScanBlock.NOT_CONNECTED, ModulesUiState().scanBlock)
    }

    @Test
    fun `not connected - no scan, the last stored scan is shown`() = runTest {
        val stored = StoredModuleScan(
            500L, '6',
            listOf(StoredModule("7D1/7D9", ModuleKind.ABS_ESC, ModuleDtcRead.Codes(ModuleDtcService.UDS_19_02, emptyList()))),
        )
        val vm = VehicleModulesViewModel(FakeConnectionController(), describer, FakeScans(stored), now = { 1_000L }, ticks = flowOf(Unit))
        val s = vm.uiState.value
        assertFalse(s.connected)
        assertFalse(s.canScan)
        assertTrue(s.fromHistory)
        assertEquals(500L, s.scannedAtMs)
        assertEquals(listOf(0), s.rows.map { it.dtcCount })
    }
}

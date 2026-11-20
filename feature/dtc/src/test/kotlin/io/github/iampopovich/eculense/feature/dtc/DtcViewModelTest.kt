package io.github.iampopovich.eculense.feature.dtc

import io.github.iampopovich.eculense.core.data.DtcRepository
import io.github.iampopovich.eculense.core.data.ReadinessReason
import io.github.iampopovich.eculense.core.dtcdb.DtcInfo
import io.github.iampopovich.eculense.core.dtcdb.DtcSource
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.FreezeFrame
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ClearedCounters
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.FakeCar
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import io.github.iampopovich.eculense.core.obd.FakeSession
import io.github.iampopovich.eculense.core.obd.ObdConnectionManager
import io.github.iampopovich.eculense.core.obd.ObdError
import io.github.iampopovich.eculense.core.obd.ObdTransport
import io.github.iampopovich.eculense.core.obd.Readiness
import io.github.iampopovich.eculense.core.obd.RecentClearVerdict
import io.github.iampopovich.eculense.core.obd.ScriptedTransport
import io.github.iampopovich.eculense.core.obd.TransportFactory
import io.github.iampopovich.eculense.core.obdtext.R as ObdTextR
import io.github.iampopovich.eculense.core.ui.UiText
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

private class FakeDtcRepository(private val session: FakeSession? = null) : DtcRepository {
    val saved = mutableListOf<Dtc>()
    /** Every successful read passed to the repository (the repository itself drops duplicates). */
    val reads = mutableListOf<List<Dtc>>()
    val frames = mutableListOf<FreezeFrame?>()
    val readiness = mutableListOf<Pair<ReadinessReason, Readiness?>>()
    /** How many clears had happened at write time: the snapshot must come before the clear. */
    val clearCallsAtRecord = mutableListOf<Int>()
    override suspend fun saveSnapshot(dtcs: List<Dtc>, vin: String?, takenAtMs: Long?, carId: Long?) {
        saved += dtcs
    }
    override suspend fun recordRead(dtcs: List<Dtc>, vin: String?): Boolean {
        reads += dtcs
        clearCallsAtRecord += session?.clearCalls ?: 0
        return true
    }
    override suspend fun recordRead(dtcs: List<Dtc>, vin: String?, freezeFrame: FreezeFrame?): Boolean {
        if (freezeFrame != null) frames += freezeFrame
        return recordRead(dtcs, vin)
    }
    override suspend fun saveReadiness(reason: ReadinessReason, readiness: Readiness?, counters: ClearedCounters?) {
        this.readiness += reason to readiness
    }
}

/** The real ELM327 path (Elm327Session via ObdConnectionManager) on top of a scripted adapter. */
private class ScriptedFactory(private val replies: Map<String, String>) : TransportFactory {
    override val kind = "fake"
    override suspend fun discover() = listOf(ENDPOINT)
    override fun create(endpoint: AdapterEndpoint): ObdTransport = ScriptedTransport(replies)

    companion object {
        val ENDPOINT = AdapterEndpoint("fake", "00:11", "Fake ELM")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DtcViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val p0133 = Dtc("P0133", DtcKind.STORED)
    private val descriptions = DtcDescriber { codes ->
        codes.associateWith { if (it == "P0133") DtcInfo(it, "O2 Sensor Circuit Slow Response", DtcSource.OBDEX) else DtcInfo(it, null, DtcSource.OEM_UNKNOWN) }
    }

    @Test
    fun `history of the active car is grouped per code with the same descriptions`() = runTest {
        val history = DtcHistorySource {
            listOf(DtcHistoryEvent("P1847", DtcKind.PENDING, 1_000), DtcHistoryEvent("P0133", DtcKind.STORED, 2_000), DtcHistoryEvent("P0133", DtcKind.STORED, 3_000))
        }
        val vm = DtcViewModel(FakeConnectionController(session = null), descriptions, FakeDtcRepository(), emptyList(), history)
        val items = vm.uiState.value.history
        assertEquals(listOf("P0133" to 2, "P1847" to 1), items.map { it.code to it.count })
        assertEquals(DtcInfo("P1847", null, DtcSource.OEM_UNKNOWN), items.last().info)
    }

    @Test
    fun `ask ai prompt carries what is known about the code`() {
        val info = DtcInfo("P0171", "System Too Lean Bank 1", DtcSource.OBDEX, causes = listOf("Air leak", "MAF", "Fuel pump", "Extra"))
        assertEquals(
            "Объясни код ошибки P0171 (System Too Lean Bank 1). Частые причины по базе OBDex: Air leak; MAF; Fuel pump: причины, что проверить, насколько срочно.",
            askAiPrompt("P0171", info, ruStrings),
        )
        assertTrue(askAiPrompt("P1847", DtcInfo("P1847", null, DtcSource.OEM_UNKNOWN), ruStrings).contains("расшифровка неизвестна"))
    }

    @Test
    fun `read shows codes with descriptions`() = runTest {
        val controller = FakeConnectionController(session = FakeSession(dtcs = listOf(p0133)))
        val vm = DtcViewModel(controller, descriptions, FakeDtcRepository(), routeHandlers = emptyList())
        vm.read()
        val item = vm.uiState.value.items.single()
        assertEquals("P0133", item.dtc.code)
        assertEquals("O2 Sensor Circuit Slow Response", item.description)
        assertFalse(vm.uiState.value.canAskAi)
    }

    @Test
    fun `every successful read is recorded in the history`() = runTest {
        val p0171 = Dtc("P0171", DtcKind.PENDING)
        val repository = FakeDtcRepository()
        val vm = DtcViewModel(FakeConnectionController(session = FakeSession(dtcs = listOf(p0133, p0171))), descriptions, repository, emptyList())
        vm.read()
        vm.read()
        assertEquals(listOf(listOf(p0133, p0171), listOf(p0133, p0171)), repository.reads)
    }

    @Test
    fun `a read without connection records nothing`() = runTest {
        val repository = FakeDtcRepository()
        val vm = DtcViewModel(FakeConnectionController(session = null), descriptions, repository, emptyList())
        vm.read()
        assertTrue(repository.reads.isEmpty())
    }

    @Test
    fun `a real ELM327 read through the connection manager is recorded`() = runTest {
        val manager = ObdConnectionManager(listOf(ScriptedFactory(FakeCar.replies)), backgroundScope)
        manager.connect(ScriptedFactory.ENDPOINT)
        val state = manager.state.first { it is ConnectionState.Connected || it is ConnectionState.Failed }
        assertTrue("$state", state is ConnectionState.Connected)
        val repository = FakeDtcRepository()
        val vm = DtcViewModel(manager, descriptions, repository, emptyList())
        vm.read()
        vm.uiState.first { it.checked && !it.loading }
        assertEquals(listOf(listOf("P0133", "P0300")), repository.reads.map { r -> r.map { it.code } })
    }

    @Test
    fun `clear records the snapshot first then clears and re-reads`() = runTest {
        val session = FakeSession(dtcs = listOf(p0133))
        val repository = FakeDtcRepository(session)
        val vm = DtcViewModel(FakeConnectionController(session = session), descriptions, repository, emptyList())
        vm.read()
        vm.clear(stationaryConfirmed = true)
        // read + snapshot before clearing (identical, the repository drops the duplicate); both before the clear
        assertEquals(listOf(listOf(p0133), listOf(p0133)), repository.reads)
        assertEquals(listOf(0, 0), repository.clearCallsAtRecord)
        assertEquals(1, session.clearCalls)
        assertEquals(emptyList<Any>(), vm.uiState.value.items)
    }

    // Readiness: MIL + 2 codes, all complete; after the clear 3 monitors incomplete. Freeze frame for P0133.
    private val diagReplies = mapOf(
        "0101" to "41 01 82 07 65 00",
        "0130" to "41 30 0C",
        "0131" to "41 31 04 E2",
        "020000" to "42 00 00 40 08 00 00",
        "020200" to "42 02 00 01 33",
        "020D00" to "42 0D 00 3F",
    )
    private val afterClearReplies = mapOf("0101" to "41 01 00 07 65 25", "0130" to "41 30 00", "0131" to "41 31 00 00")
    private val connectedState = FakeConnectionController.connected(setOf(0x0D, 0x30, 0x31))

    @Test
    fun `read shows the freeze frame on its code, readiness and the used-car check`() = runTest {
        val session = FakeSession(dtcs = listOf(p0133, Dtc("P0171", DtcKind.PENDING)), replies = diagReplies)
        val repository = FakeDtcRepository(session)
        val vm = DtcViewModel(FakeConnectionController(initial = connectedState, session = session), descriptions, repository, emptyList())
        vm.read()
        val s = vm.uiState.value
        assertEquals(63.0, s.items.first { it.dtc.code == "P0133" }.freezeFrame!!.value("01.0D")!!, 1e-9)
        assertEquals(null, s.items.first { it.dtc.code == "P0171" }.freezeFrame)
        assertTrue(s.readiness!!.milOn)
        assertTrue(s.readiness!!.allComplete)
        assertEquals(RecentClearVerdict.NO_SIGNS, s.usedCar!!.verdict)
        assertEquals(1250, s.usedCar!!.counters.kmSinceClear)
        assertEquals("P0133", repository.frames.single()?.dtcCode)
    }

    @Test
    fun `clear saves readiness before and after and shows the comparison`() = runTest {
        val session = FakeSession(dtcs = listOf(p0133), replies = diagReplies, afterClear = afterClearReplies)
        val repository = FakeDtcRepository(session)
        val vm = DtcViewModel(FakeConnectionController(initial = connectedState, session = session), descriptions, repository, emptyList())
        vm.read()
        vm.clear(stationaryConfirmed = true)
        val c = vm.uiState.value.comparison!!
        assertEquals(listOf("P0133"), c.codesBefore)
        assertEquals(emptyList<String>(), c.codesAfter)
        assertEquals(true to false, c.milBefore to c.milAfter)
        assertEquals(0 to 3, c.incompleteBefore to c.incompleteAfter)
        assertEquals(listOf(ReadinessReason.BEFORE_CLEAR, ReadinessReason.AFTER_CLEAR), repository.readiness.map { it.first })
        assertEquals(RecentClearVerdict.LIKELY_CLEARED, vm.uiState.value.usedCar!!.verdict)
    }

    @Test
    fun `clear is refused while the car moves and without confirmation when speed is unknown`() = runTest {
        var t = 10_000L
        val session = FakeSession(dtcs = listOf(p0133))
        val controller = FakeConnectionController(initial = connectedState, session = session)
        val vm = DtcViewModel(controller, descriptions, FakeDtcRepository(session), emptyList(), now = { t })
        assertEquals(listOf(0x0D), controller.polledBy.value.getValue(DtcViewModel.OWNER).map { it.code })
        controller.readingsFlow.emit(SensorReading("01.0D", 30.0, "km/h", 0))
        assertEquals(SpeedGate.MOVING, vm.uiState.value.speed)
        vm.clear(stationaryConfirmed = true)
        assertEquals(0, session.clearCalls)
        assertEquals(UiText.Res(R.string.dtc_clear_blocked_moving), vm.uiState.value.error)
        controller.readingsFlow.emit(SensorReading("01.0D", 0.0, "km/h", 0))
        assertEquals(SpeedGate.STOPPED, vm.uiState.value.speed)
        t += 2_500
        controller.readingsFlow.emit(SensorReading("01.0C", 800.0, "rpm", 0))
        assertEquals(SpeedGate.UNKNOWN, DtcViewModel.gateOf(0.0 to 10_000L, t))
        vm.clear()
        assertEquals(0, session.clearCalls)
        vm.onClearedForTest()
        assertTrue(DtcViewModel.OWNER !in controller.polledBy.value)
    }

    @Test
    fun `a clear refused by the ECU shows the typed error`() = runTest {
        val session = FakeSession(dtcs = listOf(p0133), clearError = ObdError.ClearRefused(0x22))
        val vm = DtcViewModel(FakeConnectionController(session = session), descriptions, FakeDtcRepository(session), emptyList())
        vm.clear(stationaryConfirmed = true)
        assertEquals(UiText.Res(ObdTextR.string.obd_error_clear_refused_conditions), vm.uiState.value.error)
    }

    @Test
    fun `empty read is reported as no errors`() = runTest {
        val vm = DtcViewModel(FakeConnectionController(session = FakeSession()), descriptions, FakeDtcRepository(), emptyList())
        assertFalse(vm.uiState.value.checked)
        vm.read()
        assertTrue(vm.uiState.value.checked)
        assertEquals(emptyList<Any>(), vm.uiState.value.items)
    }

    @Test
    fun `not connected gives an error message`() = runTest {
        val vm = DtcViewModel(FakeConnectionController(session = null), descriptions, FakeDtcRepository(), emptyList())
        vm.read()
        assertEquals(UiText.Res(R.string.dtc_err_no_connection), vm.uiState.value.error)
        assertFalse(vm.uiState.value.checked)
    }
}

/** The Russian resource texts the prompt test checks (JVM tests have no resources). */
private val ruStrings: (Int, List<Any>) -> String = { id, args ->
    val format = when (id) {
        R.string.dtc_ask_ai_code -> "Объясни код ошибки %1${'$'}s"
        R.string.dtc_ask_ai_oem_unknown -> "заводской код производителя, расшифровка неизвестна"
        R.string.dtc_ask_ai_causes -> "Частые причины по базе OBDex: %1${'$'}s"
        R.string.dtc_ask_ai_tail -> "причины, что проверить, насколько срочно."
        else -> error("unexpected id ${'$'}id")
    }
    format.format(*args.toTypedArray())
}

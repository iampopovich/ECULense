package io.github.iampopovich.eculense.feature.dtc

import io.github.iampopovich.eculense.core.data.DtcRepository
import io.github.iampopovich.eculense.core.dtcdb.DtcInfo
import io.github.iampopovich.eculense.core.dtcdb.DtcSource
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.FakeCar
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import io.github.iampopovich.eculense.core.obd.FakeSession
import io.github.iampopovich.eculense.core.obd.ObdConnectionManager
import io.github.iampopovich.eculense.core.obd.ObdTransport
import io.github.iampopovich.eculense.core.obd.ScriptedTransport
import io.github.iampopovich.eculense.core.obd.TransportFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

private class FakeDtcRepository(private val session: FakeSession? = null) : DtcRepository {
    val saved = mutableListOf<Dtc>()
    /** Каждое успешное чтение, переданное в репозиторий (дубли отсекает сам репозиторий). */
    val reads = mutableListOf<List<Dtc>>()
    /** Сколько сбросов уже было в момент записи — снимок обязан идти до сброса. */
    val clearCallsAtRecord = mutableListOf<Int>()
    override suspend fun saveSnapshot(dtcs: List<Dtc>, vin: String?, takenAtMs: Long?, carId: Long?) {
        saved += dtcs
    }
    override suspend fun recordRead(dtcs: List<Dtc>, vin: String?): Boolean {
        reads += dtcs
        clearCallsAtRecord += session?.clearCalls ?: 0
        return true
    }
}

/** Настоящий путь ELM327 (Elm327Session через ObdConnectionManager) поверх скриптового адаптера. */
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
        assertEquals("Заводской код производителя, расшифровка неизвестна", items.last().info.text)
    }

    @Test
    fun `ask ai prompt carries what is known about the code`() {
        val info = DtcInfo("P0171", "System Too Lean Bank 1", DtcSource.OBDEX, causes = listOf("Air leak", "MAF", "Fuel pump", "Extra"))
        assertEquals(
            "Объясни код ошибки P0171 (System Too Lean Bank 1). Частые причины по базе OBDex: Air leak; MAF; Fuel pump: причины, что проверить, насколько срочно.",
            askAiPrompt("P0171", info),
        )
        assertTrue(askAiPrompt("P1847", DtcInfo("P1847", null, DtcSource.OEM_UNKNOWN)).contains("расшифровка неизвестна"))
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
        vm.clear()
        // чтение + снимок перед сбросом (одинаковые — дубль отсекает репозиторий); оба до сброса
        assertEquals(listOf(listOf(p0133), listOf(p0133)), repository.reads)
        assertEquals(listOf(0, 0), repository.clearCallsAtRecord)
        assertEquals(1, session.clearCalls)
        assertEquals(emptyList<Any>(), vm.uiState.value.items)
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
        assertEquals("Нет подключения к адаптеру", vm.uiState.value.error)
        assertFalse(vm.uiState.value.checked)
    }
}

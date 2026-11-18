package io.github.iampopovich.eculense.feature.dtc

import io.github.iampopovich.eculense.core.data.DtcRepository
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import io.github.iampopovich.eculense.core.obd.FakeSession
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

private class FakeDtcRepository : DtcRepository {
    val saved = mutableListOf<Dtc>()
    override suspend fun saveSnapshot(dtcs: List<Dtc>, vin: String?, takenAtMs: Long?, carId: Long?) {
        saved += dtcs
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DtcViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val p0133 = Dtc("P0133", DtcKind.STORED)
    private val descriptions = DtcDescriptions { if (it == "P0133") "O2 Sensor Circuit Slow Response" else null }

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
    fun `clear saves snapshot first then clears and re-reads`() = runTest {
        val session = FakeSession(dtcs = listOf(p0133))
        val repository = FakeDtcRepository()
        val vm = DtcViewModel(FakeConnectionController(session = session), descriptions, repository, emptyList())
        vm.read()
        vm.clear()
        assertEquals(listOf(p0133), repository.saved)
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

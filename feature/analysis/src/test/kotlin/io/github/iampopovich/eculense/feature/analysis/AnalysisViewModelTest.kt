package io.github.iampopovich.eculense.feature.analysis

import io.github.iampopovich.eculense.core.analytics.DtcEvent
import io.github.iampopovich.eculense.core.analytics.Evidence
import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.FindingArg
import io.github.iampopovich.eculense.core.analytics.FindingKey
import io.github.iampopovich.eculense.core.analytics.FindingText
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.data.AnalysisState
import io.github.iampopovich.eculense.core.data.AnalyticsRepository
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.SensorSummary
import io.github.iampopovich.eculense.core.data.SessionReport
import io.github.iampopovich.eculense.core.data.SessionSummary
import io.github.iampopovich.eculense.core.data.StoredFinding
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.SessionSource
import io.github.iampopovich.eculense.core.navigation.AiChatDestination
import io.github.iampopovich.eculense.core.navigation.RouteHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

private class OneCar : CarRepository {
    val car = CarProfile(id = 1, name = "Test car")
    override fun observeCars(): Flow<List<CarProfile>> = flowOf(listOf(car))
    override fun observeActiveCar(): Flow<CarProfile?> = flowOf(car)
    override suspend fun activeCarId(): Long = 1
    override suspend fun cars() = listOf(car)
    override suspend fun byExternalId(externalId: String): CarProfile? = null
    override suspend fun save(car: CarProfile) = car.id
    override suspend fun delete(id: Long) = false
    override suspend fun setActive(id: Long) = Unit
}

private class FakeAnalytics(initial: List<StoredFinding> = emptyList()) : AnalyticsRepository {
    val stored = MutableStateFlow(initial)
    var recomputes = 0
    override val state = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    override fun observeFindings(carId: Long) = stored.map { l -> l.filter { it.carId == carId } }
    override suspend fun findings(carId: Long) = stored.value.filter { it.carId == carId }
    override suspend fun finding(id: Long) = stored.value.firstOrNull { it.id == id }
    override suspend fun recompute(carId: Long): List<Finding> {
        recomputes++
        val f = Finding(
            "coolant-overheat", Severity.WARNING, FindingText(FindingKey.TITLE_COOLANT_OVERHEAT), FindingText(FindingKey.COOLANT_OK, FindingArg.Num(105.0, 0)),
            listOf(Evidence(FindingText(FindingKey.EVIDENCE_COOLANT_MAX), FindingText(FindingKey.VALUE_CELSIUS, FindingArg.Num(106.0)))), listOf(7),
        )
        stored.value = listOf(StoredFinding(1, carId, f, 10))
        state.value = AnalysisState.Done(10, 1234, 74)
        return listOf(f)
    }
    override suspend fun sessions(carId: Long, fromMs: Long, toMs: Long, limit: Int) =
        listOf(SessionSummary(7, 100, 200, SessionSource.IMPORT_CARSCANNER, 5), SessionSummary(8, 300, 400, SessionSource.LIVE, 1))
    override suspend fun sessionReport(carId: Long, sessionId: Long): SessionReport? = null
    override suspend fun sensorSummary(carId: Long, sensorId: String, fromMs: Long, toMs: Long, warmOnly: Boolean): SensorSummary? = null
    override suspend fun dtcEvents(carId: Long): List<DtcEvent> = emptyList()
}

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `first open recomputes when there are no findings yet`() = runTest {
        val analytics = FakeAnalytics()
        val vm = AnalysisViewModel(analytics, OneCar())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(1, analytics.recomputes)
        val s = vm.uiState.value
        assertEquals("Test car", s.car?.name)
        assertEquals(listOf("coolant-overheat"), s.problems.map { it.finding.ruleId })
        assertTrue(s.analysis is AnalysisState.Done)
    }

    @Test
    fun `existing findings are shown without recompute, button recomputes`() = runTest {
        val f = StoredFinding(5, 1, Finding("voltage-low", Severity.OK, FindingText(FindingKey.TITLE_VOLTAGE_LOW), FindingText(FindingKey.VOLTAGE_LOW_OK, FindingArg.Num(13.2))), 1)
        val analytics = FakeAnalytics(listOf(f))
        val vm = AnalysisViewModel(analytics, OneCar())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(0, analytics.recomputes)
        assertEquals(listOf(f), vm.uiState.value.ok)
        vm.recompute()
        assertEquals(1, analytics.recomputes)
    }

    @Test
    fun `finding details with sessions and ask ai only when chat exists`() = runTest {
        val analytics = FakeAnalytics().apply { recompute(1) }
        val withAi = FindingViewModel(1, analytics, listOf(RouteHandler(AiChatDestination::class)))
        assertTrue(withAi.uiState.value.canAskAi)
        assertEquals(listOf(7L), withAi.uiState.value.sessions.map { it.id })
        assertFalse(FindingViewModel(1, analytics, emptyList()).uiState.value.canAskAi)
    }

    @Test
    fun `dtc evidence of a finding gets descriptions, other labels are not looked up`() = runTest {
        val f = Finding(
            "dtc-other", Severity.INFO, FindingText(FindingKey.TITLE_DTC_OTHER), FindingText(FindingKey.DTC_OTHER, FindingArg.Raw("P1847×1")),
            listOf(
                Evidence(FindingText.raw("P1847"), FindingText(FindingKey.DTC_OCCURRENCES, FindingArg.Count(1), FindingArg.DateRange(1, 1))),
                Evidence(FindingText(FindingKey.EVIDENCE_MEDIAN), FindingText.raw("P0000")),
            ),
        )
        val analytics = FakeAnalytics(listOf(StoredFinding(3, 1, f, 10)))
        val asked = mutableListOf<Collection<String>>()
        val vm = FindingViewModel(3, analytics, emptyList()) { codes -> asked += codes; codes.associateWith { DtcNote("OEM code") } }
        assertEquals(listOf(listOf("P1847")), asked.map { it.toList() })
        assertEquals("OEM code", vm.uiState.value.dtc["P1847"]?.text)
        // only raw DTC labels are looked up: a typed label whose value happens to look like a code is not
        assertEquals(1, vm.uiState.value.dtc.size)
    }

    @Test
    fun `findings stored before localization are recomputed once into typed text`() = runTest {
        val legacy = StoredFinding(5, 1, Finding("voltage-low", Severity.OK, FindingText.raw("old title"), FindingText.raw("old text")), 1)
        val analytics = FakeAnalytics(listOf(legacy))
        val vm = AnalysisViewModel(analytics, OneCar())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(1, analytics.recomputes)
        assertFalse(vm.uiState.value.findings.any { it.finding.isLegacyText })
    }
}

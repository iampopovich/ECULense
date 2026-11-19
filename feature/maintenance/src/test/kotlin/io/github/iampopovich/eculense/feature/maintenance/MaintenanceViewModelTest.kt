package io.github.iampopovich.eculense.feature.maintenance

import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import io.github.iampopovich.eculense.core.analytics.MaintenanceRule
import io.github.iampopovich.eculense.core.analytics.MaintenanceStatus
import io.github.iampopovich.eculense.core.analytics.MaintenanceTemplate
import io.github.iampopovich.eculense.core.analytics.OdometerEstimate
import io.github.iampopovich.eculense.core.analytics.OdometerEstimator
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.MaintenanceRepository
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import io.github.iampopovich.eculense.core.model.OdometerEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

private class OneCar(car: CarProfile) : CarRepository {
    val cars = MutableStateFlow(listOf(car))
    override fun observeCars() = cars
    override fun observeActiveCar(): Flow<CarProfile?> = cars.map { it.firstOrNull() }
    override suspend fun activeCarId() = cars.value.firstOrNull()?.id
    override suspend fun cars() = cars.value
    override suspend fun byExternalId(externalId: String) = null
    override suspend fun save(car: CarProfile) = car.id
    override suspend fun delete(id: Long) = false
    override suspend fun setActive(id: Long) = Unit
}

/** In-memory maintenance on top of the real rules (MaintenanceRule, OdometerEstimator). */
private class MemMaintenance(private val now: Long) : MaintenanceRepository {
    val items = MutableStateFlow<List<MaintenanceItem>>(emptyList())
    val log = MutableStateFlow<List<OdometerEntry>>(emptyList())
    var seeded = 0
    private var next = 1L
    override fun observeItems(carId: Long) = items.map { l -> l.filter { it.carId == carId } }
    override suspend fun items(carId: Long) = items.value.filter { it.carId == carId }
    override suspend fun ensureTemplate(car: CarProfile): Boolean {
        if (seeded++ > 0) return false
        MaintenanceTemplate.forCar(car).forEach { save(it) }
        return true
    }
    override suspend fun save(item: MaintenanceItem): Long {
        if (item.id == 0L) return next++.also { items.value = items.value + item.copy(id = it) }
        items.value = items.value.map { if (it.id == item.id) item else it }
        return item.id
    }
    override suspend fun delete(id: Long) { items.value = items.value.filterNot { it.id == id } }
    override suspend fun markDone(id: Long, km: Long?, atMs: Long): MaintenanceItem? {
        val before = items.value.firstOrNull { it.id == id } ?: return null
        save(before.copy(lastDoneKm = km, lastDoneAtMs = atMs))
        return before
    }
    override fun observeOdometer(carId: Long) = log
    override suspend fun addOdometer(carId: Long, km: Long, atMs: Long) = next++.also { log.value = log.value + OdometerEntry(it, carId, km, atMs) }
    override suspend fun deleteOdometer(id: Long) { log.value = log.value.filterNot { it.id == id } }
    override fun observeEstimate(carId: Long): Flow<OdometerEstimate?> = log.map { OdometerEstimator.estimate(it, emptyList()) }
    override suspend fun estimate(carId: Long) = observeEstimate(carId).first()
    override suspend fun evaluate(carId: Long) = MaintenanceRule.evaluateAll(items(carId), estimate(carId)?.km, now, ZoneOffset.UTC)
    override suspend fun due(carId: Long) = MaintenanceRule.due(items(carId), estimate(carId)?.km, now, ZoneOffset.UTC)
}

@OptIn(ExperimentalCoroutinesApi::class)
class MaintenanceViewModelTest {
    private val now = LocalDate.parse("2026-06-01").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val repo = MemMaintenance(now)
    private val car = CarProfile(id = 1, name = "Тестовая")

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = MaintenanceViewModel(OneCar(car), repo, clock = { now }, zone = ZoneOffset.UTC)

    @Test
    fun `first open seeds the template and shows statuses`() = runTest {
        val vm = vm()
        val state = vm.uiState.first { !it.loading && it.items.isNotEmpty() }
        assertEquals(1, repo.seeded)
        assertTrue(state.items.all { it.status == MaintenanceStatus.UNKNOWN })
        assertNull(state.estimate)
    }

    @Test
    fun `odometer entry and its undo leave the log as it was`() = runTest {
        val vm = vm()
        vm.addOdometer(10_000)
        val withOdo = vm.uiState.first { it.estimate != null }
        assertEquals(10_000.0, withOdo.estimate!!.km, 0.0)
        assertTrue(withOdo.undo is Undo.Odometer)
        vm.undo()
        assertNull(vm.uiState.first { it.estimate == null && it.undo == null }.estimate)
        assertTrue(repo.log.value.isEmpty())
    }

    @Test
    fun `mark done then undo restores the previous item`() = runTest {
        val vm = vm()
        val item = vm.uiState.first { it.items.isNotEmpty() }.items.first { it.item.title.startsWith("Масло и") }.item
        vm.markDone(item.id, 9_000, now)
        val done = vm.uiState.first { s -> s.items.any { it.item.id == item.id && it.item.lastDoneKm == 9_000L } }
        assertTrue(done.undo is Undo.Item)
        assertEquals(MaintenanceStatus.OK, done.items.first { it.item.id == item.id }.status)
        vm.undo()
        val restored = vm.uiState.first { s -> s.items.any { it.item.id == item.id && it.item.lastDoneKm == null } }
        assertEquals(MaintenanceStatus.UNKNOWN, restored.items.first { it.item.id == item.id }.status)
    }

    @Test
    fun `deleted item comes back on undo`() = runTest {
        val vm = vm()
        val count = vm.uiState.first { it.items.isNotEmpty() }.items.size
        val item = vm.uiState.value.items.first().item
        vm.delete(item)
        vm.uiState.first { it.items.size == count - 1 }
        vm.undo()
        val back = vm.uiState.first { it.items.size == count }
        assertTrue(back.items.any { it.item.title == item.title })
    }
}

class MaintenanceFormatTest {
    private val zone = ZoneOffset.UTC
    private fun ms(d: String) = LocalDate.parse(d).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `numbers dates and remaining texts`() {
        assertEquals("120\u00A0000\u00A0км", MaintenanceFormat.km(120_000))
        assertEquals("0,2\u00A0км", MaintenanceFormat.addedKm(0.23))
        assertEquals("1\u00A0234\u00A0км", MaintenanceFormat.addedKm(1_234.4))
        assertEquals("Каждые 10\u00A0000\u00A0км или 12\u00A0мес.", MaintenanceFormat.interval(MaintenanceItem(carId = 1, title = "x", intervalKm = 10_000, intervalMonths = 12)))
        assertEquals("Интервал не задан", MaintenanceFormat.interval(MaintenanceItem(carId = 1, title = "x")))
        val item = MaintenanceItem(carId = 1, title = "x", intervalKm = 10_000, intervalMonths = 12, lastDoneKm = 50_000, lastDoneAtMs = ms("2026-01-01"))
        assertEquals("просрочено на 300\u00A0км · через 7\u00A0мес.", MaintenanceFormat.remaining(MaintenanceDue(item, MaintenanceStatus.OVERDUE, 60_000, -300.0, 0, 214)))
        assertEquals("Последний раз: 01.01.2026, 50\u00A0000\u00A0км", MaintenanceFormat.lastDone(item, zone))
        assertEquals("Отметьте, когда делали", MaintenanceFormat.remaining(MaintenanceDue(item.copy(lastDoneKm = null, lastDoneAtMs = null), MaintenanceStatus.UNKNOWN)))
    }

    @Test
    fun `input parsing rejects garbage and the future`() {
        assertEquals(120_000L, MaintenanceFormat.parseKm("120 000"))
        assertNull(MaintenanceFormat.parseKm("12a"))
        assertNull(MaintenanceFormat.parseKm("-5"))
        assertEquals(ms("2026-05-31") + 12 * 3_600_000, MaintenanceFormat.parseDate("31.05.2026", zone, ms("2026-06-01")))
        assertNull(MaintenanceFormat.parseDate("02.06.2026", zone, ms("2026-06-01")))
        assertNull(MaintenanceFormat.parseDate("2026-05-31", zone, ms("2026-06-01")))
    }
}

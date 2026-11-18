package io.github.iampopovich.eculense.feature.car

import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.TripRepository
import io.github.iampopovich.eculense.core.data.UnitsRepository
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.StatsPeriod
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import io.github.iampopovich.eculense.core.model.UnitsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
import java.time.ZoneOffset

internal class MemCars : CarRepository {
    val all = MutableStateFlow(listOf(CarProfile(id = 1, name = CarProfile.DEFAULT_NAME)))
    val active = MutableStateFlow<Long?>(null)
    private var next = 2L
    override fun observeCars(): Flow<List<CarProfile>> = all
    override fun observeActiveCar(): Flow<CarProfile?> = combine(all, active) { cars, id -> cars.firstOrNull { it.id == id } ?: cars.firstOrNull() }
    override suspend fun activeCarId() = active.value ?: all.value.firstOrNull()?.id
    override suspend fun cars() = all.value
    override suspend fun byExternalId(externalId: String) = all.value.firstOrNull { it.externalId == externalId }
    override suspend fun save(car: CarProfile): Long =
        if (car.id == 0L) next++.also { id -> all.value = all.value + car.copy(id = id) }
        else car.id.also { all.value = all.value.map { c -> if (c.id == car.id) car else c } }
    override suspend fun delete(id: Long): Boolean {
        if (all.value.size <= 1) return false
        all.value = all.value.filterNot { it.id == id }
        return true
    }
    override suspend fun setActive(id: Long) {
        active.value = id
    }
}

internal class MemTrips(trips: List<Trip>) : TripRepository {
    val all = MutableStateFlow(trips)
    override fun observeTrips(carId: Long) = all.map { list -> list.filter { it.carId == carId } }
    override suspend fun addAll(trips: List<Trip>) = trips.size.also { all.value = all.value + trips }
}

internal class MemUnits : UnitsRepository {
    override val units = MutableStateFlow(UnitsSettings())
    override suspend fun save(units: UnitsSettings) {
        this.units.value = units
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CarViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    private fun trip(car: Long, ago: Long, km: Double, l: Double) =
        Trip(carId = car, startedAtMs = now - ago, endedAtMs = now - ago + 1000, distanceKm = km, fuelL = l, fuelPricePerL = 60.0,
            drivingMs = 3_600_000, standingMs = 600_000, source = TripSource.IMPORT_CARSCANNER)

    @Test
    fun `stats follow the active car and the period`() = runTest {
        val cars = MemCars().apply { save(CarProfile(name = "Вторая")) }
        val trips = MemTrips(listOf(trip(1, day, 10.0, 1.0), trip(1, 100 * day, 90.0, 9.0), trip(2, day, 5.0, 2.0)))
        val vm = CarViewModel(cars, trips, MemUnits(), clock = { now }, zone = ZoneOffset.UTC)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertEquals(100.0, vm.uiState.value.stats.distanceKm, 1e-9)
        assertEquals(10.0, vm.uiState.value.stats.avgLPer100Km!!, 1e-9)
        assertEquals(2, vm.uiState.value.months.size)

        vm.selectPeriod(StatsPeriod.MONTH)
        assertEquals(10.0, vm.uiState.value.stats.distanceKm, 1e-9)

        vm.setActive(2)
        assertEquals("Вторая", vm.uiState.value.active!!.name)
        assertEquals(5.0, vm.uiState.value.stats.distanceKm, 1e-9)
        assertEquals(1, vm.uiState.value.totalTrips)
    }

    @Test
    fun `units change is saved`() = runTest {
        val units = MemUnits()
        val vm = CarViewModel(MemCars(), MemTrips(emptyList()), units, clock = { now })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.setUnits(UnitsSettings(metricDistance = false))
        assertFalse(units.units.value.metricDistance)
        assertFalse(vm.uiState.value.units.metricDistance)
    }

    @Test
    fun `editor creates a new active car and blocks invalid input`() = runTest {
        val cars = MemCars()
        val vm = CarEditViewModel(0, cars, clock = { now })
        assertTrue(vm.state.value.isNew)
        assertFalse(vm.state.value.canDelete)
        vm.save() // пустое имя — ничего не сохраняется
        assertEquals(1, cars.all.value.size)

        vm.edit { it.copy(name = "Новая", year = "2015") }
        assertTrue(vm.state.value.errors.isEmpty())
        vm.save()
        assertTrue(vm.state.value.done)
        assertEquals(listOf(CarProfile.DEFAULT_NAME, "Новая"), cars.all.value.map { it.name })
        assertEquals(2L, cars.active.value)
    }

    @Test
    fun `editor edits and deletes, but never the last car`() = runTest {
        val cars = MemCars()
        val only = CarEditViewModel(1, cars, clock = { now })
        assertFalse(only.state.value.canDelete)
        only.delete()
        assertEquals(1, cars.all.value.size)

        cars.save(CarProfile(name = "Вторая"))
        val vm = CarEditViewModel(2, cars, clock = { now })
        assertTrue(vm.state.value.canDelete)
        vm.edit { it.copy(brand = "Lada") }
        vm.save()
        assertEquals("Lada", cars.all.value.last().brand)
        CarEditViewModel(2, cars, clock = { now }).delete()
        assertEquals(listOf(1L), cars.all.value.map { it.id })
    }
}

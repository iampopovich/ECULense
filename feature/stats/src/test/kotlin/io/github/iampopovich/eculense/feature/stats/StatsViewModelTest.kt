package io.github.iampopovich.eculense.feature.stats

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
import org.junit.Before
import org.junit.Test

private class Cars : CarRepository {
    val all = MutableStateFlow(listOf(CarProfile(id = 1, name = "Первая"), CarProfile(id = 2, name = "Вторая")))
    val active = MutableStateFlow<Long?>(null)
    override fun observeCars() = all
    override fun observeActiveCar() = combine(all, active) { cars, id -> cars.firstOrNull { it.id == id } ?: cars.firstOrNull() }
    override suspend fun activeCarId() = active.value ?: 1L
    override suspend fun cars() = all.value
    override suspend fun byExternalId(externalId: String): CarProfile? = null
    override suspend fun save(car: CarProfile) = car.id
    override suspend fun delete(id: Long) = false
    override suspend fun setActive(id: Long) {
        active.value = id
    }
}

private class Trips(list: List<Trip>) : TripRepository {
    val all = MutableStateFlow(list)
    override fun observeTrips(carId: Long) = all.map { l -> l.filter { it.carId == carId } }
    override suspend fun addAll(trips: List<Trip>) = 0
}

private class Units : UnitsRepository {
    override val units = MutableStateFlow(UnitsSettings())
    override suspend fun save(units: UnitsSettings) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    private fun trip(car: Long, ago: Long, km: Double, l: Double) =
        Trip(carId = car, startedAtMs = now - ago, endedAtMs = now - ago + 1000, distanceKm = km, fuelL = l, fuelPricePerL = 60.0,
            drivingMs = 3_600_000, standingMs = 600_000, source = TripSource.IMPORT_CARSCANNER, externalId = "t$car$ago")

    @Test
    fun `stats and trip list follow the active car and the period`() = runTest {
        val cars = Cars()
        val trips = Trips(listOf(trip(1, 3 * day, 10.0, 1.0), trip(1, 20 * day, 30.0, 3.0), trip(1, 100 * day, 60.0, 6.0), trip(2, day, 5.0, 2.0)))
        val vm = StatsViewModel(cars, trips, Units(), clock = { now })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        val all = vm.uiState.value
        assertEquals("Первая", all.car!!.name)
        assertEquals(3, all.stats.trips)
        assertEquals(100.0, all.stats.distanceKm, 1e-9)
        assertEquals(10.0, all.stats.avgLPer100Km!!, 1e-9)
        assertEquals(listOf(now - 3 * day, now - 20 * day, now - 100 * day), all.trips.map { it.startedAtMs })

        vm.selectPeriod(StatsPeriod.WEEK)
        assertEquals(1, vm.uiState.value.trips.size)
        vm.selectPeriod(StatsPeriod.MONTH)
        assertEquals(40.0, vm.uiState.value.stats.distanceKm, 1e-9)
        assertEquals(3, vm.uiState.value.totalTrips)

        cars.setActive(2)
        assertEquals("Вторая", vm.uiState.value.car!!.name)
        assertEquals(5.0, vm.uiState.value.stats.distanceKm, 1e-9)
        assertEquals(1, vm.uiState.value.totalTrips)
    }

    @Test
    fun `no trips at all`() = runTest {
        val vm = StatsViewModel(Cars(), Trips(emptyList()), Units(), clock = { now })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(0, vm.uiState.value.totalTrips)
        assertEquals(false, vm.uiState.value.loading)
    }
}

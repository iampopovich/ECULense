package io.github.iampopovich.eculense.feature.widget

import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.InMemoryDashboardPrefs
import io.github.iampopovich.eculense.core.data.LastSeen
import io.github.iampopovich.eculense.core.data.LastSeenStore
import io.github.iampopovich.eculense.core.data.TripRepository
import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.EndpointLabel
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private class Cars(private val id: Long?) : CarRepository {
    override fun observeCars(): Flow<List<CarProfile>> = flowOf(listOfNotNull(id?.let { CarProfile(id = it, name = "Тестовая") }))
    override fun observeActiveCar(): Flow<CarProfile?> = flowOf(id?.let { CarProfile(id = it, name = "Тестовая") })
    override suspend fun activeCarId() = id
    override suspend fun cars() = emptyList<CarProfile>()
    override suspend fun byExternalId(externalId: String) = null
    override suspend fun save(car: CarProfile) = car.id
    override suspend fun delete(id: Long) = false
    override suspend fun setActive(id: Long) = Unit
}

private class Seen(private val seen: LastSeen?, private val fail: Boolean = false) : LastSeenStore {
    override fun observe(carId: Long): Flow<LastSeen> = flow { if (fail) error("datastore") else emit(seen ?: LastSeen()) }
    override suspend fun saveVoltage(carId: Long, volts: Double, atMs: Long) = Unit
    override suspend fun saveDtcCheck(carId: Long, count: Int, atMs: Long) = Unit
}

private class Trips(private val trips: List<Trip>, private val fail: Boolean = false) : TripRepository {
    override fun observeTrips(carId: Long): Flow<List<Trip>> = flow { if (fail) error("db") else emit(trips.filter { it.carId == carId }) }
    override suspend fun addAll(trips: List<Trip>) = 0
}

/** WidgetRepository.current(): the model the widget shows for live, demo and idle states (Robolectric for Context). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetRepositoryTest {
    private val now = 10_000_000L
    private val info = AdapterInfo("ELM327 v1.5", '6', true, 12.6)
    private val real = ConnectionState.Connected(AdapterEndpoint("bt", "00:00:00:00:00:01", "OBDII"), info, setOf(0x0C, 0x0D, 0x05))
    private val demo = ConnectionState.Connected(AdapterEndpoint("demo", "virtual", "Demo", EndpointLabel.DEMO), info, setOf(0x0C, 0x0D, 0x05))

    private fun trip(endedAt: Long, km: Double) = Trip(carId = 1, startedAtMs = endedAt - 600_000, endedAtMs = endedAt, distanceKm = km, fuelL = 1.0, fuelPricePerL = null, drivingMs = 500_000, standingMs = 100_000, source = TripSource.LIVE)

    @Test
    fun `connected shows the latest live values of the dashboard selection, a demo link is marked`() = runTest {
        val controller = FakeConnectionController(initial = real)
        val repo = WidgetRepository(
            RuntimeEnvironment.getApplication(), controller, InMemoryDashboardPrefs(selection = listOf(0x0C, 0x0D, 0x05)), Cars(1), Seen(null), Trips(emptyList()),
            backgroundScope, clock = { now },
        )
        repo.start()
        testScheduler.runCurrent()
        controller.readingsFlow.emit(SensorReading("01.0C", 812.0, "rpm", now))
        testScheduler.runCurrent()
        val live = repo.current() as WidgetModel.Live
        assertEquals(false, live.simulated)
        assertEquals(3, live.values.size)
        assertTrue(live.values.any { it.value == 812.0 })

        controller.stateFlow.value = demo
        val shown = repo.current() as WidgetModel.Live
        assertTrue(shown.simulated)
    }

    @Test
    fun `idle shows the last voltage, code check and the newest trip with their ages`() = runTest {
        val seen = LastSeen(voltage = 12.4, voltageAtMs = now - 60_000, dtcCount = 0, dtcCheckedAtMs = now - 3_600_000)
        val repo = WidgetRepository(
            RuntimeEnvironment.getApplication(), FakeConnectionController(initial = ConnectionState.Disconnected), InMemoryDashboardPrefs(), Cars(1),
            Seen(seen), Trips(listOf(trip(now - 7_200_000, 12.0), trip(now - 120_000, 3.5))), backgroundScope, clock = { now },
        )
        val idle = repo.current() as WidgetModel.Idle
        assertEquals(12.4, idle.voltage!!, 1e-9)
        assertEquals(60_000L, idle.voltageAgeMs)
        assertEquals(0, idle.dtcCount)
        assertEquals(3.5, idle.tripDistance!!, 1e-9)
        assertEquals(120_000L, idle.tripAgeMs)
    }

    @Test
    fun `no active car or failing stores still give an empty idle widget`() = runTest {
        val disconnected = FakeConnectionController(initial = ConnectionState.Disconnected)
        val noCar = WidgetRepository(RuntimeEnvironment.getApplication(), disconnected, InMemoryDashboardPrefs(), Cars(null), Seen(null), Trips(emptyList()), backgroundScope)
        assertEquals(WidgetModel.Idle(), noCar.current())
        val failing = WidgetRepository(
            RuntimeEnvironment.getApplication(), disconnected, InMemoryDashboardPrefs(), Cars(1), Seen(null, fail = true), Trips(emptyList(), fail = true), backgroundScope,
            clock = { now },
        )
        val idle = failing.current() as WidgetModel.Idle
        assertEquals(null, idle.voltage)
        assertEquals(null, idle.tripDistance)
    }
}

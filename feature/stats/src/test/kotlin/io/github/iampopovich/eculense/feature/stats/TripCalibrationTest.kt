package io.github.iampopovich.eculense.feature.stats

import io.github.iampopovich.eculense.core.analytics.CalibrationGap
import io.github.iampopovich.eculense.core.analytics.SpeedSample
import io.github.iampopovich.eculense.core.analytics.StyleThresholds
import io.github.iampopovich.eculense.core.data.AccelCalibrations
import io.github.iampopovich.eculense.core.data.AccelFiles
import io.github.iampopovich.eculense.core.data.AccelSource
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.InMemoryStylePrefs
import io.github.iampopovich.eculense.core.data.TripRepository
import io.github.iampopovich.eculense.core.data.UnitsRepository
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import io.github.iampopovich.eculense.core.model.UnitsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TripCalibrationTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val t0 = 1_700_000_000_000L
    private val g = StyleThresholds.G

    private val trip = Trip(
        id = 1, carId = 1, startedAtMs = t0, endedAtMs = t0 + 3_600_000, distanceKm = 20.0, fuelL = 0.0, fuelPricePerL = null,
        drivingMs = 1_000_000, standingMs = 0, source = TripSource.LIVE, externalId = "session:5",
    )

    private val cars = object : CarRepository {
        override fun observeCars() = flowOf(listOf(CarProfile(id = 1, name = "Test car")))
        override fun observeActiveCar() = flowOf(CarProfile(id = 1, name = "Test car"))
        override suspend fun activeCarId() = 1L
        override suspend fun cars() = listOf(CarProfile(id = 1, name = "Test car"))
        override suspend fun byExternalId(externalId: String): CarProfile? = null
        override suspend fun save(car: CarProfile) = car.id
        override suspend fun delete(id: Long) = false
        override suspend fun setActive(id: Long) = Unit
    }
    private val trips = object : TripRepository {
        override fun observeTrips(carId: Long) = flowOf(listOf(trip))
        override suspend fun addAll(trips: List<Trip>) = 0
        override suspend fun trip(id: Long): Trip? = trip.takeIf { it.id == id }
    }
    private val units = object : UnitsRepository {
        override val units = MutableStateFlow(UnitsSettings())
        override suspend fun save(units: UnitsSettings) = Unit
    }

    /** Phone lying flat, x = forward: [brakes] brakings at 0.35 g for 3 s, gentle driving between; OBD every 1 s. */
    private fun writeDrive(files: AccelFiles, brakes: Int): List<SpeedSample> {
        val accel = mutableListOf<Pair<Long, Double>>()
        repeat(brakes) { i ->
            accel += 20_000L to (if (i % 2 == 0) 0.06 else -0.06) * g
            accel += 3_000L to -0.35 * g
            accel += 15_000L to 0.07 * g
        }
        val total = accel.sumOf { it.first }
        fun a(t: Long): Double { var s = 0L; for ((d, v) in accel) { if (t < s + d) return v; s += d }; return 0.0 }
        val speed = mutableListOf<SpeedSample>()
        var v = 90.0
        files.writer(5, AccelSource.LINEAR_ACCELERATION, t0).use { w ->
            for (t in 0..total step 20) {
                w.linear(t0 + t, a(t).toFloat(), 0f, 0f)
                if (t % 1000 == 0L) w.gravity(t0 + t, 0f, 0f, 9.81f)
                v += a(t) * 3.6 * 0.02
                if (t % 1000 == 0L) speed += SpeedSample(t0 + t, Math.round(v).toDouble())
            }
        }
        return speed
    }

    private fun vm(files: AccelFiles, speed: List<SpeedSample>, prefs: InMemoryStylePrefs) = TripDetailViewModel(
        1, trips, cars, units, sessions = null, events = null,
        calibrations = AccelCalibrations(files, speed = { _, from, to -> speed.filter { it.tMs in from..to } }, carSessions = { listOf(5L) }),
        stylePrefs = prefs, exportDir = tmp.newFolder("exports"),
    )

    private fun TripDetailViewModel.awaitCalibration(check: (CalibrationUi) -> Boolean = { true }): CalibrationUi {
        repeat(200) {
            state.value.calibration?.takeIf { !it.driverLoading && check(it) }?.let { return it }
            Thread.sleep(20)
        }
        error("calibration not loaded: ${state.value}")
    }

    @Test
    fun `a calibrated recording can be applied to the car and reset`() = runBlocking {
        val files = AccelFiles(tmp.newFolder("accel"))
        val speed = writeDrive(files, brakes = 6)
        val prefs = InMemoryStylePrefs()
        val vm = vm(files, speed, prefs)
        val ui = vm.awaitCalibration()
        assertNull(ui.report.calibration.gap)
        assertTrue(ui.canApply)
        assertFalse(ui.overridden)
        assertEquals(StyleThresholds.HARSH_ACCEL_MS2, ui.currentMs2, 1e-9)
        assertNotNull(ui.driver)
        val recommended = ui.report.calibration.recommendedMs2!!
        vm.applyCalibration()
        val applied = vm.awaitCalibration { it.overridden }
        assertEquals(recommended, applied.currentMs2, 1e-9)
        assertEquals(recommended, prefs.harshMs2(1).first()!!, 1e-9)
        vm.resetCalibration()
        val reset = vm.awaitCalibration { !it.overridden }
        assertEquals(StyleThresholds.HARSH_ACCEL_MS2, reset.currentMs2, 1e-9)
        assertNull(prefs.harshMs2(1).first())
        val csv = vm.exportCsv()!!
        assertTrue(csv.readLines().first().startsWith("t_ms,a_long,a_lat,obd_speed,obd_windowed_derivative"))
    }

    @Test
    fun `a still phone shows not enough data and nothing to apply`() = runBlocking {
        val files = AccelFiles(tmp.newFolder("accel"))
        files.writer(5, AccelSource.LINEAR_ACCELERATION, t0).use { w ->
            for (t in 0..30_000L step 20) { w.linear(t0 + t, 0.01f, -0.01f, 0.02f); if (t % 1000 == 0L) w.gravity(t0 + t, 0f, 0f, 9.81f) }
        }
        val speed = (0..30).map { SpeedSample(t0 + it * 1000L, 0.0) }
        val ui = vm(files, speed, InMemoryStylePrefs()).awaitCalibration()
        assertEquals(CalibrationGap.NO_MOVEMENT, ui.report.calibration.gap)
        assertFalse(ui.canApply)
        assertEquals(1501, ui.report.samples)
    }

    @Test
    fun `no accelerometer file, no card`() = runBlocking {
        val vm = vm(AccelFiles(tmp.newFolder("accel")), emptyList(), InMemoryStylePrefs())
        repeat(100) { if (!vm.state.value.loading) return@repeat; Thread.sleep(20) }
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.calibration)
    }
}

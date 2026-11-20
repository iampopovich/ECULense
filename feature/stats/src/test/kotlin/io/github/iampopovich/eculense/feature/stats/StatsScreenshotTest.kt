package io.github.iampopovich.eculense.feature.stats

import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.FuelMethod
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import io.github.iampopovich.eculense.core.model.TripStats
import io.github.iampopovich.eculense.core.screenshots.snapshotMatrix
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** C13: Stats header (title + action row + period selector) and summary; synthetic trips only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StatsScreenshotTest {
    private val base = 1_790_000_000_000L
    private val trips = List(4) { i ->
        Trip(
            id = i + 1L, carId = 1, startedAtMs = base - i * 86_400_000L, endedAtMs = base - i * 86_400_000L + 1_800_000,
            distanceKm = 12.5 + i, fuelL = 1.4 + i * 0.1, fuelPricePerL = 1.5, drivingMs = 1_500_000, standingMs = 300_000,
            source = TripSource.LIVE, fuelMethod = FuelMethod.MAF_ESTIMATE,
        )
    }
    private val state = StatsUiState(
        loading = false,
        car = CarProfile(id = 1, name = "Test car", brand = "Testmake", model = "Model", year = 2015),
        trips = trips,
        totalTrips = trips.size,
        stats = TripStats.of(trips),
        consumption = 11.2,
        fuelCost = 8.4,
    )

    @Test
    fun stats() = snapshotMatrix("stats") {
        StatsScreen(state, onPeriod = {}, actions = HeaderActions(onImport = {}, onFuel = {}, onExpenses = {}))
    }
}

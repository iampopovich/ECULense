package io.github.iampopovich.eculense.feature.stats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.StatsPeriod
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import io.github.iampopovich.eculense.core.model.TripStats
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int, vararg args: Any): String = ctx.getString(id, *args)
    private fun n(v: Double, decimals: Int = 1) = StatsFormat.number(v, decimals)
    private val km get() = str(CoreUiR.string.common_unit_km)
    private val l get() = str(CoreUiR.string.common_unit_l)
    private val lPer100 get() = str(CoreUiR.string.common_unit_l_per_100km)

    private val car = CarProfile(id = 1, name = "Тестовая", currency = "RUB")
    private val trip = Trip(id = 1, carId = 1, startedAtMs = 1_767_000_000_000, endedAtMs = 1_767_001_200_000, distanceKm = 12.5,
        fuelL = 1.1, fuelPricePerL = 60.0, drivingMs = 1_000_000, standingMs = 200_000, source = TripSource.IMPORT_CARSCANNER)

    @Test
    fun summaryAndTripsAreShown() {
        val stats = TripStats(trips = 12, distanceKm = 1234.5, fuelL = 98.7, drivingMs = 7_200_000, standingMs = 1_800_000, fuelCost = 5_971.0)
        val periods = mutableListOf<StatsPeriod>()
        rule.setContent {
            StatsScreen(StatsUiState(loading = false, car = car, stats = stats, trips = listOf(trip), totalTrips = 12), onPeriod = { periods += it })
        }
        rule.onNodeWithText("Тестовая").assertExists()
        rule.onNodeWithText("${n(1234.5)} $km").assertExists()
        rule.onNodeWithText("${n(8.0)} $lPer100").assertExists()
        rule.onNodeWithText(str(R.string.stats_duration_hm, 2, 0)).assertExists()
        rule.onNodeWithText("${n(5971.0, 0)} RUB").assertExists()
        rule.onNodeWithText("${n(12.5)} $km · ${n(1.1)} $l · ${n(8.8)} $lPer100").assertExists()
        rule.onNodeWithText(str(R.string.stats_period_week)).performClick()
        assertEquals(listOf(StatsPeriod.WEEK), periods)
        rule.onNodeWithText(str(R.string.stats_period_all)).assertExists()
    }

    @Test
    fun liveTripsShowTheirSourceAndEstimates() {
        val maf = trip.copy(id = 2, source = TripSource.LIVE, fuelMethod = io.github.iampopovich.eculense.core.model.FuelMethod.MAF_ESTIMATE)
        val none = trip.copy(id = 3, source = TripSource.LIVE, startedAtMs = trip.startedAtMs - 86_400_000, fuelL = 0.0,
            fuelMethod = io.github.iampopovich.eculense.core.model.FuelMethod.NOT_AVAILABLE)
        rule.setContent {
            StatsScreen(StatsUiState(loading = false, car = car, stats = TripStats.of(listOf(trip, maf, none)), trips = listOf(trip, maf, none), totalTrips = 3), onPeriod = {})
        }
        rule.onNodeWithText("Car Scanner").assertExists()
        rule.onAllNodesWithText(str(R.string.stats_source_ecu)).assertCountEquals(2)
        rule.onNodeWithText("${n(12.5)} $km · ≈ ${n(1.1)} $l · ≈ ${n(8.8)} $lPer100").assertExists()
        rule.onNodeWithText("${n(12.5)} $km · " + str(R.string.stats_fuel_unknown)).assertExists()
    }

    @Test
    fun noTripsAtAllShowsPlaceholderWithoutImportWhenBackupIsMissing() {
        rule.setContent { StatsScreen(StatsUiState(loading = false, car = car), onPeriod = {}) }
        rule.onNodeWithText(str(R.string.stats_no_trips_at_all)).assertExists()
        rule.onNodeWithText(str(R.string.stats_empty_hint)).assertExists()
        rule.onNodeWithText(str(R.string.stats_import)).assertDoesNotExist()
        // the period toggle is present even without data
        rule.onNodeWithText(str(R.string.stats_period_week)).assertExists()
    }

    @Test
    fun importButtonOpensBackupScreen() {
        var clicks = 0
        rule.setContent { StatsScreen(StatsUiState(loading = false, car = car), onPeriod = {}, onImport = { clicks++ }) }
        rule.onNodeWithText(str(R.string.stats_import)).performClick()
        assertEquals(1, clicks)
    }

    /** One import button per screen for any period and state — not «импорт за неделю/месяц». */
    @Test
    fun exactlyOneImportButtonInEveryPeriodAndState() {
        var state by mutableStateOf(StatsUiState(loading = false, car = car))
        rule.setContent { StatsScreen(state, onPeriod = { state = state.copy(period = it) }, onImport = {}) }
        val states = listOf(
            StatsUiState(loading = false, car = car),
            StatsUiState(loading = false, car = car, totalTrips = 5),
            StatsUiState(loading = false, car = car, stats = TripStats.of(listOf(trip)), trips = listOf(trip), totalTrips = 1),
        )
        for (s in states) for (p in StatsFormat.periods) {
            state = s.copy(period = p)
            rule.waitForIdle()
            rule.onAllNodesWithText(str(R.string.stats_import)).assertCountEquals(1)
        }
    }

    @Test
    fun emptyPeriodShowsPlaceholderNamingThePeriod() {
        val expected = mapOf(
            StatsPeriod.WEEK to R.string.stats_no_trips_week,
            StatsPeriod.MONTH to R.string.stats_no_trips_month,
            StatsPeriod.YEAR to R.string.stats_no_trips_year,
            StatsPeriod.ALL to R.string.stats_no_trips_all,
        )
        var state by mutableStateOf(StatsUiState(loading = false, car = car, totalTrips = 5))
        rule.setContent { StatsScreen(state, onPeriod = {}) }
        for ((p, title) in expected) {
            state = state.copy(period = p)
            rule.waitForIdle()
            rule.onNodeWithText(str(title)).assertExists()
            rule.onNodeWithText(str(R.string.stats_empty_hint)).assertExists()
        }
    }

    @Test
    fun tripsInPeriodHidePlaceholder() {
        rule.setContent {
            StatsScreen(StatsUiState(loading = false, car = car, period = StatsPeriod.WEEK, stats = TripStats.of(listOf(trip)),
                trips = listOf(trip), totalTrips = 1), onPeriod = {}, onImport = {})
        }
        StatsFormat.periods.forEach { rule.onNodeWithText(str(StatsFormat.emptyTitle(it, 1))).assertDoesNotExist() }
        rule.onNodeWithText(str(R.string.stats_no_trips_at_all)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.stats_trips)).assertExists()
    }
}

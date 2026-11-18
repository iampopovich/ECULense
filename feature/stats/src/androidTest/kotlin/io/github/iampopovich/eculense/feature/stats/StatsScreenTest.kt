package io.github.iampopovich.eculense.feature.stats

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.StatsPeriod
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import io.github.iampopovich.eculense.core.model.TripStats
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatsScreenTest {
    @get:Rule
    val rule = createComposeRule()

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
        rule.onNodeWithText("1\u00A0234,5 км").assertExists()
        rule.onNodeWithText("8,0 л/100 км").assertExists()
        rule.onNodeWithText("2 ч 00 мин").assertExists()
        rule.onNodeWithText("5\u00A0971 RUB").assertExists()
        rule.onNodeWithText("12,5 км · 1,1 л · 8,8 л/100 км").assertExists()
        rule.onNodeWithText("Неделя").performClick()
        assertEquals(listOf(StatsPeriod.WEEK), periods)
        rule.onNodeWithText("Всё время").assertExists()
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
        rule.onAllNodesWithText("Запись ECULense").assertCountEquals(2)
        rule.onNodeWithText("12,5 км · ≈ 1,1 л · ≈ 8,8 л/100 км").assertExists()
        rule.onNodeWithText("12,5 км · топливо: оценка недоступна").assertExists()
    }

    @Test
    fun emptyStateExplainsImport() {
        rule.setContent { StatsScreen(StatsUiState(loading = false, car = car), onPeriod = {}) }
        rule.onNodeWithText("Импорт из Car Scanner", substring = true).assertExists()
    }

    @Test
    fun emptyPeriodSuggestsLongerOne() {
        rule.setContent { StatsScreen(StatsUiState(loading = false, car = car, totalTrips = 5), onPeriod = {}) }
        rule.onNodeWithText("За этот период поездок нет", substring = true).assertExists()
    }
}

package io.github.iampopovich.eculense.feature.car

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.TripStats
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class CarScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val car = CarProfile(id = 1, name = "Тестовая", brand = "Lada", year = 2010, fuelPrice = 60.5, currency = "RUB")

    @Test
    fun statsAreShownForTheActiveCar() {
        val stats = TripStats(trips = 12, distanceKm = 1234.5, fuelL = 98.7, drivingMs = 7_200_000, standingMs = 1_800_000, fuelCost = 5_971.0)
        val state = CarUiState(loading = false, cars = listOf(car), active = car, stats = stats, totalTrips = 12,
            months = listOf(YearMonth.of(2026, 1) to stats))
        rule.setContent { CarScreen(state, onEdit = {}, onAdd = {}, onSelect = {}, onPeriod = {}, onUnits = {}) }
        rule.onNodeWithText("Lada · 2010").assertExists()
        rule.onNodeWithText("1\u00A0234,5 км").assertExists()
        rule.onNodeWithText("5\u00A0971 RUB").assertExists()
        rule.onNodeWithText("8,0 л/100 км").assertExists()
        rule.onNodeWithText("2 ч 00 мин").assertExists()
        rule.onNodeWithText("Цена топлива: 60,50 RUB/л").assertExists()
    }

    @Test
    fun emptyTripsExplainImport() {
        val state = CarUiState(loading = false, cars = listOf(car), active = car)
        rule.setContent { CarScreen(state, onEdit = {}, onAdd = {}, onSelect = {}, onPeriod = {}, onUnits = {}) }
        rule.onNodeWithText("Импорт из Car Scanner", substring = true).assertExists()
    }

    @Test
    fun saveIsLockedUntilTheFormIsValid() {
        var saves = 0
        val invalid = CarForm(name = "")
        rule.setContent {
            CarEditScreen(
                CarEditUiState(loading = false, form = invalid, errors = invalid.errors(2026)),
                onEdit = {}, onSave = { saves++ }, onDelete = {},
            )
        }
        rule.onNodeWithText("Укажите название").assertExists()
        rule.onNodeWithText("Сохранить").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun validFormSaves() {
        var saves = 0
        val form = CarForm(name = "Моя")
        rule.setContent { CarEditScreen(CarEditUiState(loading = false, form = form), onEdit = {}, onSave = { saves++ }, onDelete = {}) }
        rule.onNodeWithText("Сохранить").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, saves)
    }
}

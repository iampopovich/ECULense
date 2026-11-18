package io.github.iampopovich.eculense.feature.car

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iampopovich.eculense.core.model.CarProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val car = CarProfile(id = 1, name = "Тестовая", brand = "Lada", year = 2010, fuelPrice = 60.5, currency = "RUB")

    @Test
    fun activeCarProfileIsShown() {
        val state = CarUiState(loading = false, cars = listOf(car), active = car)
        rule.setContent { CarScreen(state, onEdit = {}, onAdd = {}, onSelect = {}, onUnits = {}) }
        rule.onNodeWithText("Lada · 2010").assertExists()
        rule.onNodeWithText("Цена топлива: 60,50 RUB/л").assertExists()
        rule.onNodeWithText("Статистика поездок").assertDoesNotExist()
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

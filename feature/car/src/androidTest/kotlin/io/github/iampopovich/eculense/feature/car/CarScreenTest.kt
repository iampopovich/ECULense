package io.github.iampopovich.eculense.feature.car

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iampopovich.eculense.core.datapacks.PackCatalog
import io.github.iampopovich.eculense.core.datapacks.PackState
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.vehicledb.VehicleEngine
import io.github.iampopovich.eculense.core.vehicledb.VehicleGeneration
import io.github.iampopovich.eculense.core.vehicledb.VehicleMake
import io.github.iampopovich.eculense.core.vehicledb.VehicleModel
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

    @Test
    fun withoutCatalogTheEditorOffersDownloadAndKeepsManualInput() {
        var downloads = 0
        val form = CarForm(name = "Тестовая")
        rule.setContent {
            CarEditScreen(
                CarEditUiState(loading = false, form = form, vehiclePack = PackState(PackCatalog.vehicles)),
                onEdit = {}, onSave = {}, onDelete = {}, onDownloadCatalog = { downloads++ },
            )
        }
        rule.onNodeWithText("Справочник моделей не скачан", substring = true).assertExists()
        rule.onNodeWithText("Скачать справочник").performClick()
        assertEquals(1, downloads)
        rule.onNodeWithText("Заполнить из справочника").assertDoesNotExist()
        rule.onNodeWithText("Масса, кг").performScrollTo().assertExists()
        rule.onNodeWithText("Полный").performScrollTo().assertExists()
    }

    @Test
    fun withCatalogTheEditorOpensThePicker() {
        var opens = 0
        rule.setContent {
            CarEditScreen(
                CarEditUiState(loading = false, form = CarForm(name = "Тестовая"), catalogAvailable = true),
                onEdit = {}, onSave = {}, onDelete = {}, onOpenPicker = { opens++ },
            )
        }
        rule.onNodeWithText("Заполнить из справочника").performClick()
        assertEquals(1, opens)
    }

    @Test
    fun pickerShowsSpecsAndFills() {
        var fills = 0
        val engine = VehicleEngine(1, "3.5L V6 4AT AWD (195 HP)", "Gasoline", 6, 3497, 194.0, 294.0, "4-Speed automatic", "All Wheel Drive", 11.9, 1917, tankL = 79.9)
        val state = PickerState(PickerStep.ENGINE, make = VehicleMake(1, "Kia"), model = VehicleModel(2, "Sorento", 2002, null),
            generation = VehicleGeneration(3, "Sorento (2002)", 2002, 2006, null), engine = engine)
        rule.setContent { VehiclePickerDialog(state, onSearch = {}, onChoose = {}, onBack = {}, onFill = { fills++ }, onClose = {}) }
        rule.onNodeWithText("Kia › Sorento › Sorento (2002) (2002–2006)").assertExists()
        rule.onNodeWithText("79,9 л").assertExists()
        rule.onNodeWithText("1917 кг").assertExists()
        rule.onNodeWithText("Заполнить").performScrollTo().performClick()
        assertEquals(1, fills)
    }

    @Test
    fun pickerListsOptionsAndChooses() {
        val chosen = mutableListOf<Long>()
        val state = PickerState(PickerStep.MODEL, make = VehicleMake(1, "Kia"), options = listOf(PickerOption(10, "Sorento", "2002–н. в.")))
        rule.setContent { VehiclePickerDialog(state, onSearch = {}, onChoose = { chosen += it.id }, onBack = {}, onFill = {}, onClose = {}) }
        rule.onNodeWithText("Справочник: модель").assertExists()
        rule.onNodeWithText("Sorento").performClick()
        assertEquals(listOf(10L), chosen)
    }
}

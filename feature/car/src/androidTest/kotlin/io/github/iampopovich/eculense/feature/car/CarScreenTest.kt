package io.github.iampopovich.eculense.feature.car

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import io.github.iampopovich.eculense.core.ui.UiText

@RunWith(AndroidJUnit4::class)
class CarScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val car = CarProfile(id = 1, name = "Test car", brand = "Lada", year = 2010, fuelPrice = 60.5, currency = "RUB")
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int, vararg args: Any) = ctx.getString(id, *args)

    @Test
    fun activeCarProfileIsShown() {
        val state = CarUiState(loading = false, cars = listOf(car), active = car)
        rule.setContent { CarScreen(state, onEdit = {}, onAdd = {}, onSelect = {}, onUnits = {}) }
        rule.onNodeWithText("Lada · 2010").assertExists()
        rule.onNodeWithText(str(R.string.car_fuel_price_line, CarFormat.number(60.5, 2), "RUB")).assertExists()
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
        rule.onNodeWithText(str(R.string.car_error_name)).assertExists()
        rule.onNodeWithText(str(CoreUiR.string.common_save)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun validFormSaves() {
        var saves = 0
        val form = CarForm(name = "Mine")
        rule.setContent { CarEditScreen(CarEditUiState(loading = false, form = form), onEdit = {}, onSave = { saves++ }, onDelete = {}) }
        rule.onNodeWithText(str(CoreUiR.string.common_save)).performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, saves)
    }

    @Test
    fun withoutCatalogTheEditorOffersDownloadAndKeepsManualInput() {
        var downloads = 0
        val form = CarForm(name = "Test car")
        rule.setContent {
            CarEditScreen(
                CarEditUiState(loading = false, form = form, vehiclePack = PackState(PackCatalog.vehicles)),
                onEdit = {}, onSave = {}, onDelete = {}, onDownloadCatalog = { downloads++ },
            )
        }
        rule.onNodeWithText(str(R.string.car_catalog_missing)).assertExists()
        rule.onNodeWithText(str(R.string.car_catalog_download)).performClick()
        assertEquals(1, downloads)
        rule.onNodeWithText(str(R.string.car_catalog_fill)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.car_field_mass)).performScrollTo().assertExists()
        rule.onNodeWithText(str(R.string.car_drive_all)).performScrollTo().assertExists()
    }

    @Test
    fun withCatalogTheEditorOpensThePicker() {
        var opens = 0
        rule.setContent {
            CarEditScreen(
                CarEditUiState(loading = false, form = CarForm(name = "Test car"), catalogAvailable = true),
                onEdit = {}, onSave = {}, onDelete = {}, onOpenPicker = { opens++ },
            )
        }
        rule.onNodeWithText(str(R.string.car_catalog_fill)).performClick()
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
        rule.onNodeWithText(str(R.string.car_spec_liters_value, CarFormat.number(79.9))).assertExists()
        rule.onNodeWithText(str(R.string.car_spec_mass_value, 1917)).assertExists()
        rule.onNodeWithText(str(R.string.car_picker_fill)).performScrollTo().performClick()
        assertEquals(1, fills)
    }

    @Test
    fun pickerListsOptionsAndChooses() {
        val chosen = mutableListOf<Long>()
        val state = PickerState(PickerStep.MODEL, make = VehicleMake(1, "Kia"), options = listOf(PickerOption(10, "Sorento", UiText.Res(CoreUiR.string.common_years_open, listOf(2002)))))
        rule.setContent { VehiclePickerDialog(state, onSearch = {}, onChoose = { chosen += it.id }, onBack = {}, onFill = {}, onClose = {}) }
        rule.onNodeWithText(str(R.string.car_picker_title, str(R.string.car_picker_step_model).lowercase())).assertExists()
        rule.onNodeWithText(str(CoreUiR.string.common_years_open, 2002)).assertExists()
        rule.onNodeWithText("Sorento").performClick()
        assertEquals(listOf(10L), chosen)
    }

    @Test
    fun defaultCarShowsTheLocalizedDefaultName() {
        val unnamed = car.copy(name = CarProfile.DEFAULT_NAME)
        rule.setContent { CarScreen(CarUiState(loading = false, cars = listOf(unnamed), active = unnamed), onEdit = {}, onAdd = {}, onSelect = {}, onUnits = {}) }
        rule.onAllNodesWithText(ctx.getString(CoreUiR.string.common_car_default_name))[0].assertExists()
    }
}

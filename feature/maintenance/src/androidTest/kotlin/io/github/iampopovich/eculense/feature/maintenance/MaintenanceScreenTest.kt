package io.github.iampopovich.eculense.feature.maintenance

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import io.github.iampopovich.eculense.core.analytics.MaintenanceStatus
import io.github.iampopovich.eculense.core.analytics.OdometerEstimate
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import io.github.iampopovich.eculense.core.model.OdometerEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class MaintenanceScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val zone = ZoneOffset.UTC
    private fun ms(d: String) = LocalDate.parse(d).atStartOfDay(zone).toInstant().toEpochMilli()
    private val now = ms("2026-06-01")
    private val car = CarProfile(id = 1, name = "Тестовая")
    private val oil = MaintenanceItem(id = 1, carId = 1, title = "Масло и масляный фильтр двигателя", intervalKm = 10_000, intervalMonths = 12, lastDoneKm = 50_000, lastDoneAtMs = ms("2026-01-01"))
    private val brake = MaintenanceItem(id = 2, carId = 1, title = "Тормозная жидкость", intervalMonths = 24)

    private fun state(estimate: OdometerEstimate? = OdometerEstimate(60_300.0, 60_000, ms("2026-05-01"), 300.0)) = MaintenanceUiState(
        loading = false, car = car, estimate = estimate,
        odometerLog = estimate?.let { listOf(OdometerEntry(7, 1, it.baseKm, it.baseAtMs)) }.orEmpty(),
        items = listOf(
            MaintenanceDue(oil, MaintenanceStatus.OVERDUE, 60_000, -300.0, ms("2027-01-01"), 214),
            MaintenanceDue(brake, MaintenanceStatus.UNKNOWN),
        ),
        nowMs = now,
    )

    @Test
    fun listShowsOdometerEstimateStatusesAndHints() {
        rule.setContent { MaintenanceScreen(state(), zone, MaintenanceActions()) }
        rule.onNodeWithText("Тестовая").assertExists()
        rule.onNodeWithText("≈ 60\u00A0300\u00A0км").assertExists()
        rule.onNodeWithText("Просрочено").assertExists()
        rule.onNodeWithText("просрочено на 300\u00A0км · через 7\u00A0мес.").assertExists()
        rule.onNodeWithText("Нет данных").assertExists()
        rule.onNodeWithText("Отметьте, когда делали").assertExists()
        rule.onNodeWithText("Каждые 24\u00A0мес.").assertExists()
    }

    @Test
    fun noOdometerExplainsWhyItIsManual() {
        rule.setContent { MaintenanceScreen(state(estimate = null), zone, MaintenanceActions()) }
        rule.onNodeWithText("Не указан").assertExists()
        rule.onNodeWithText("OBD-II не передаёт", substring = true).assertExists()
        rule.onNodeWithText("Ввести пробег").assertExists()
    }

    @Test
    fun doneDialogPrefillsEstimateAndToday() {
        val calls = mutableListOf<Triple<Long, Long?, Long>>()
        rule.setContent { MaintenanceScreen(state(), zone, MaintenanceActions(markDone = { id, km, at -> calls += Triple(id, km, at) })) }
        rule.onAllNodesWithText("Сделано")[0].performClick()
        rule.onNodeWithText("Подставлена оценка", substring = true).assertExists()
        rule.onNodeWithText("Отметить").performClick()
        assertEquals(listOf(Triple(1L, 60_300L, now + 12 * 3_600_000)), calls)
    }

    @Test
    fun odometerDialogValidatesAndSaves() {
        val saved = mutableListOf<Long>()
        rule.setContent { MaintenanceScreen(state(estimate = null), zone, MaintenanceActions(addOdometer = { saved += it })) }
        rule.onNodeWithText("Ввести пробег").performClick()
        rule.onNodeWithTag("odometer-input").performTextInput("12a")
        rule.onNodeWithText("Целое число от 0 до 5 000 000").assertExists()
        rule.onNodeWithText("Сохранить").assertIsNotEnabled()
        rule.onNodeWithTag("odometer-input").performTextClearance()
        rule.onNodeWithTag("odometer-input").performTextInput("123 456")
        rule.onNodeWithText("Сохранить").performClick()
        assertEquals(listOf(123_456L), saved)
    }

    @Test
    fun editDialogChangesIntervals() {
        val saved = mutableListOf<MaintenanceItem>()
        rule.setContent { MaintenanceScreen(state(), zone, MaintenanceActions(save = { saved += it })) }
        rule.onAllNodesWithText("Изменить")[1].performClick()
        rule.onNodeWithTag("edit-km").performTextInput("40000")
        rule.onNodeWithText("Сохранить").performClick()
        assertEquals(brake.copy(intervalKm = 40_000), saved.single())
    }

    @Test
    fun addWorkButtonOpensEmptyForm() {
        rule.setContent { MaintenanceScreen(state(), zone, MaintenanceActions()) }
        rule.onNodeWithText("Добавить работу").performScrollTo().performClick()
        rule.onNodeWithText("Новая работа").assertExists()
        rule.onNodeWithText("Сохранить").assertIsNotEnabled()
    }
}

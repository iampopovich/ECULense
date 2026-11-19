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
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import io.github.iampopovich.eculense.core.analytics.MaintenanceStatus
import io.github.iampopovich.eculense.core.analytics.OdometerEstimate
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import io.github.iampopovich.eculense.core.model.MaintenanceKind
import io.github.iampopovich.eculense.core.model.OdometerEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class MaintenanceScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val zone = ZoneOffset.UTC
    private fun ms(d: String) = LocalDate.parse(d).atStartOfDay(zone).toInstant().toEpochMilli()
    private val now = ms("2026-06-01")
    private val car = CarProfile(id = 1, name = "Test car")
    // template work (kind, blank title) and the user's own work
    private val oil = MaintenanceItem(id = 1, carId = 1, title = "", kind = MaintenanceKind.OIL_AND_FILTER, intervalKm = 10_000, intervalMonths = 12, lastDoneKm = 50_000, lastDoneAtMs = ms("2026-01-01"))
    private val brake = MaintenanceItem(id = 2, carId = 1, title = "Brake fluid (own)", intervalMonths = 24)
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int, vararg args: Any) = ctx.getString(id, *args)
    private fun km(v: Long) = str(R.string.maint_km, MaintenanceFormat.number(v))

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
        rule.onNodeWithText("Test car").assertExists()
        rule.onNodeWithText(ctx.getString(io.github.iampopovich.eculense.core.analyticstext.R.string.maint_kind_oil_and_filter)).assertExists()
        rule.onNodeWithText("≈ " + km(60_300)).assertExists()
        rule.onNodeWithText(str(R.string.maint_status_overdue)).assertExists()
        rule.onNodeWithText(str(R.string.maint_overdue_by, km(300)) + " · " + str(R.string.maint_in_months, 7)).assertExists()
        rule.onNodeWithText(str(R.string.maint_status_unknown)).assertExists()
        rule.onNodeWithText(str(R.string.maint_mark_when_done)).assertExists()
        rule.onNodeWithText(str(R.string.maint_interval_every, str(R.string.maint_months_short, 24))).assertExists()
    }

    @Test
    fun noOdometerExplainsWhyItIsManual() {
        rule.setContent { MaintenanceScreen(state(estimate = null), zone, MaintenanceActions()) }
        rule.onNodeWithText(str(R.string.maint_odometer_not_set)).assertExists()
        rule.onNodeWithText(str(R.string.maint_odometer_hint)).assertExists()
        rule.onNodeWithText(str(R.string.maint_odometer_enter)).assertExists()
    }

    @Test
    fun doneDialogPrefillsEstimateAndToday() {
        val calls = mutableListOf<Triple<Long, Long?, Long>>()
        rule.setContent { MaintenanceScreen(state(), zone, MaintenanceActions(markDone = { id, km, at -> calls += Triple(id, km, at) })) }
        rule.onAllNodesWithText(str(R.string.maint_done))[0].performClick()
        rule.onNodeWithText(str(R.string.maint_done_km_estimated)).assertExists()
        rule.onNodeWithText(str(R.string.maint_mark)).performClick()
        assertEquals(listOf(Triple(1L, 60_300L, now + 12 * 3_600_000)), calls)
    }

    @Test
    fun odometerDialogValidatesAndSaves() {
        val saved = mutableListOf<Long>()
        rule.setContent { MaintenanceScreen(state(estimate = null), zone, MaintenanceActions(addOdometer = { saved += it })) }
        rule.onNodeWithText(str(R.string.maint_odometer_enter)).performClick()
        rule.onNodeWithTag("odometer-input").performTextInput("12a")
        rule.onNodeWithText(str(R.string.maint_odometer_invalid)).assertExists()
        rule.onNodeWithText(str(CoreUiR.string.common_save)).assertIsNotEnabled()
        rule.onNodeWithTag("odometer-input").performTextClearance()
        rule.onNodeWithTag("odometer-input").performTextInput("123 456")
        rule.onNodeWithText(str(CoreUiR.string.common_save)).performClick()
        assertEquals(listOf(123_456L), saved)
    }

    @Test
    fun editDialogChangesIntervals() {
        val saved = mutableListOf<MaintenanceItem>()
        rule.setContent { MaintenanceScreen(state(), zone, MaintenanceActions(save = { saved += it })) }
        rule.onAllNodesWithText(str(CoreUiR.string.common_edit))[1].performClick()
        rule.onNodeWithTag("edit-km").performTextInput("40000")
        rule.onNodeWithText(str(CoreUiR.string.common_save)).performClick()
        assertEquals(brake.copy(intervalKm = 40_000), saved.single())
    }

    @Test
    fun mistakenDoneMarkCanBeResetFromTheEditDialog() {
        val saved = mutableListOf<MaintenanceItem>()
        rule.setContent { MaintenanceScreen(state(), zone, MaintenanceActions(save = { saved += it })) }
        rule.onAllNodesWithText(str(CoreUiR.string.common_edit))[0].performClick()
        rule.onNodeWithText(str(R.string.maint_reset_done)).performClick()
        assertEquals(oil.copy(lastDoneKm = null, lastDoneAtMs = null), saved.single())
    }

    @Test
    fun addWorkButtonOpensEmptyForm() {
        rule.setContent { MaintenanceScreen(state(), zone, MaintenanceActions()) }
        rule.onNodeWithText(str(R.string.maint_add)).performScrollTo().performClick()
        rule.onNodeWithText(str(R.string.maint_new_item)).assertExists()
        rule.onNodeWithText(str(CoreUiR.string.common_save)).assertIsNotEnabled()
    }

    @Test
    fun unchangedTemplateNameStaysTyped() {
        val saved = mutableListOf<MaintenanceItem>()
        rule.setContent { MaintenanceScreen(state(), zone, MaintenanceActions(save = { saved += it })) }
        rule.onAllNodesWithText(str(CoreUiR.string.common_edit))[0].performClick()
        rule.onNodeWithTag("edit-months").performTextClearance()
        rule.onNodeWithTag("edit-months").performTextInput("6")
        rule.onNodeWithText(str(CoreUiR.string.common_save)).performClick()
        // the localized name was not edited: the item keeps its kind and a blank title, so it follows the app language
        assertEquals(oil.copy(intervalMonths = 6), saved.single())
    }
}

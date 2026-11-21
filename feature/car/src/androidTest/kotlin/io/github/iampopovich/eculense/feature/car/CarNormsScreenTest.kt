package io.github.iampopovich.eculense.feature.car

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iampopovich.eculense.core.analytics.CarNorms
import io.github.iampopovich.eculense.core.analytics.Norm
import io.github.iampopovich.eculense.core.analytics.NormKey
import io.github.iampopovich.eculense.core.analytics.NormSource
import io.github.iampopovich.eculense.core.analytics.NormText
import io.github.iampopovich.eculense.core.analyticstext.AnalyticsText
import io.github.iampopovich.eculense.core.data.CarNormsView
import io.github.iampopovich.eculense.core.data.NormRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** R-H «Car norms» screen on fake states (library module test: no app data involved). */
@RunWith(AndroidJUnit4::class)
class CarNormsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    private val baseline = Norm(NormKey.COOLANT_PLATEAU, 87.0, 97.0, NormSource.BASELINE, center = 92.0, mad = 1.0, sampleCount = 10, confidence = 0.8)
    private val user = Norm(NormKey.COOLANT_PLATEAU, 85.0, 99.0, NormSource.USER)
    private val view = CarNormsView(null, CarNorms(listOf(baseline, user)), listOf(NormRow(NormKey.COOLANT_PLATEAU, user, baseline, user)))

    @Test
    fun noCarSaysSo() {
        rule.setContent { CarNormsScreen(CarNormsUiState(loading = false), onSave = { _, _, _ -> null }, onReset = {}) }
        rule.onNodeWithText(str(R.string.car_norms_no_car)).assertExists()
    }

    @Test
    fun rowOpensTheSheetShowsInputErrorsAndResetsToTheLearnedNorm() {
        val saves = mutableListOf<Pair<String, String>>()
        var resets = 0
        rule.setContent {
            CarNormsScreen(
                CarNormsUiState(false, 1, view),
                onSave = { _, lo, hi -> saves += lo to hi; NormInputError.LOW_ABOVE_HIGH },
                onReset = { resets++ },
            )
        }
        rule.onNodeWithText(str(R.string.car_norms_src_user)).assertExists() // source chip of the row
        rule.onNodeWithText(AnalyticsText.render(ctx, NormText.name(NormKey.COOLANT_PLATEAU))).performClick()
        rule.onNode(hasSetTextAction() and hasText(str(R.string.car_norms_low))).performTextReplacement("99")
        rule.onNode(hasSetTextAction() and hasText(str(R.string.car_norms_high))).performTextReplacement("85")
        rule.onNodeWithText(str(R.string.car_norms_save)).performClick()
        rule.onNodeWithText(str(R.string.car_norms_err_order)).assertExists() // the sheet stays open with the error
        assertEquals("99" to "85", saves.single())
        rule.onNodeWithText(str(R.string.car_norms_reset_learned)).performClick()
        assertEquals(1, resets)
    }
}

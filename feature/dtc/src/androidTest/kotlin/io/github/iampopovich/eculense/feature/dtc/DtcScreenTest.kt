package io.github.iampopovich.eculense.feature.dtc

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iampopovich.eculense.core.dtcdb.DtcInfo
import io.github.iampopovich.eculense.core.dtcdb.DtcSource
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DtcScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    private val items = listOf(
        DtcItem(Dtc("P0300", DtcKind.STORED), DtcInfo("P0300", "Random/Multiple Cylinder Misfire Detected", DtcSource.OBDEX, causes = listOf("Worn spark plugs"), symptoms = listOf("Engine shakes"))),
        DtcItem(Dtc("P1234", DtcKind.PENDING), DtcInfo("P1234", null, DtcSource.OEM_UNKNOWN)),
    )

    @Test
    fun showsCodesKindsAndDescriptions() {
        rule.setContent { DtcScreen(DtcUiState(items = items), onRead = {}, onClear = {}, onAskAi = { _, _ -> }) }
        rule.onNodeWithText("P0300 · " + str(R.string.dtc_kind_stored)).assertExists()
        rule.onNodeWithText("Random/Multiple Cylinder Misfire Detected").assertExists()
        rule.onNodeWithText(str(R.string.dtc_causes, "Worn spark plugs")).assertExists()
        rule.onNodeWithText(str(R.string.dtc_symptoms, "Engine shakes")).assertExists()
        rule.onNodeWithText(str(R.string.dtc_source_obdex)).assertExists()
        rule.onNodeWithText("P1234 · " + str(R.string.dtc_kind_pending)).assertExists()
        rule.onNodeWithText("Заводской код производителя, расшифровка неизвестна").assertExists()
    }

    @Test
    fun historySectionListsPastCodesOfTheCar() {
        val history = listOf(DtcHistoryItem("P0107", DtcKind.STORED, 3, 1_767_527_136_040, DtcInfo("P0107", "Manifold Absolute Pressure Circuit Low", DtcSource.OBDEX)))
        rule.setContent { DtcScreen(DtcUiState(history = history), onRead = {}, onClear = {}, onAskAi = { _, _ -> }) }
        rule.onNodeWithText(str(R.string.dtc_history_title)).assertExists()
        rule.onNodeWithText("P0107 · " + str(R.string.dtc_kind_stored)).assertExists()
        rule.onNodeWithText(ctx.resources.getQuantityString(R.plurals.dtc_history_seen, 3, 3, "").trimEnd(), substring = true).assertExists()
        rule.onNodeWithText("Manifold Absolute Pressure Circuit Low").assertExists()
    }

    @Test
    fun askAiHiddenWithoutHandlerAndShownWithIt() {
        val state = mutableStateOf(DtcUiState(items = items, canAskAi = false))
        rule.setContent { DtcScreen(state.value, onRead = {}, onClear = {}, onAskAi = { _, _ -> }) }
        assertEquals(0, rule.onAllNodesWithText(str(R.string.dtc_ask_ai)).fetchSemanticsNodes().size)
        state.value = DtcUiState(items = items, canAskAi = true)
        rule.waitForIdle()
        assertEquals(2, rule.onAllNodesWithText(str(R.string.dtc_ask_ai)).fetchSemanticsNodes().size)
    }

    @Test
    fun clearAsksForConfirmation() {
        var cleared = 0
        rule.setContent { DtcScreen(DtcUiState(items = items), onRead = {}, onClear = { cleared++ }, onAskAi = { _, _ -> }) }
        rule.onNodeWithText(str(R.string.dtc_clear)).performClick()
        rule.onNodeWithText(str(R.string.dtc_clear_title)).assertExists()
        rule.onNodeWithText(str(CoreUiR.string.common_cancel)).performClick()
        assertEquals(0, cleared)
        rule.onNodeWithText(str(R.string.dtc_clear)).performClick()
        rule.onNode(hasText(str(R.string.dtc_clear)) and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        assertEquals(1, cleared)
    }

    @Test
    fun clearDisabledWhenNoCodes() {
        rule.setContent { DtcScreen(DtcUiState(), onRead = {}, onClear = {}, onAskAi = { _, _ -> }) }
        rule.onNodeWithText(str(R.string.dtc_clear)).assertIsNotEnabled()
        rule.onNodeWithText(str(R.string.dtc_no_codes)).assertDoesNotExist()
    }

    @Test
    fun emptyCheckedListSaysNoErrors() {
        rule.setContent { DtcScreen(DtcUiState(checked = true), onRead = {}, onClear = {}, onAskAi = { _, _ -> }) }
        rule.onNodeWithText(str(R.string.dtc_no_codes)).assertExists()
    }
}

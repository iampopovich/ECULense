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
import io.github.iampopovich.eculense.core.dtcdb.DtcInfo
import io.github.iampopovich.eculense.core.dtcdb.DtcSource
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DtcScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val items = listOf(
        DtcItem(Dtc("P0300", DtcKind.STORED), DtcInfo("P0300", "Random/Multiple Cylinder Misfire Detected", DtcSource.OBDEX, causes = listOf("Worn spark plugs"), symptoms = listOf("Engine shakes"))),
        DtcItem(Dtc("P1234", DtcKind.PENDING), DtcInfo("P1234", null, DtcSource.OEM_UNKNOWN)),
    )

    @Test
    fun showsCodesKindsAndDescriptions() {
        rule.setContent { DtcScreen(DtcUiState(items = items), onRead = {}, onClear = {}, onAskAi = { _, _ -> }) }
        rule.onNodeWithText("P0300 · сохранённая").assertExists()
        rule.onNodeWithText("Random/Multiple Cylinder Misfire Detected").assertExists()
        rule.onNodeWithText("Причины: Worn spark plugs").assertExists()
        rule.onNodeWithText("Симптомы: Engine shakes").assertExists()
        rule.onNodeWithText("Источник: OBDex (CC0)").assertExists()
        rule.onNodeWithText("P1234 · ожидающая").assertExists()
        rule.onNodeWithText("Заводской код производителя, расшифровка неизвестна").assertExists()
    }

    @Test
    fun historySectionListsPastCodesOfTheCar() {
        val history = listOf(DtcHistoryItem("P0107", DtcKind.STORED, 3, 1_767_527_136_040, DtcInfo("P0107", "Manifold Absolute Pressure Circuit Low", DtcSource.OBDEX)))
        rule.setContent { DtcScreen(DtcUiState(history = history), onRead = {}, onClear = {}, onAskAi = { _, _ -> }) }
        rule.onNodeWithText("История кодов этой машины").assertExists()
        rule.onNodeWithText("P0107 · сохранённая").assertExists()
        rule.onNodeWithText("3 раза", substring = true).assertExists()
        rule.onNodeWithText("Manifold Absolute Pressure Circuit Low").assertExists()
    }

    @Test
    fun askAiHiddenWithoutHandlerAndShownWithIt() {
        val state = mutableStateOf(DtcUiState(items = items, canAskAi = false))
        rule.setContent { DtcScreen(state.value, onRead = {}, onClear = {}, onAskAi = { _, _ -> }) }
        assertEquals(0, rule.onAllNodesWithText("Спросить ИИ").fetchSemanticsNodes().size)
        state.value = DtcUiState(items = items, canAskAi = true)
        rule.waitForIdle()
        assertEquals(2, rule.onAllNodesWithText("Спросить ИИ").fetchSemanticsNodes().size)
    }

    @Test
    fun clearAsksForConfirmation() {
        var cleared = 0
        rule.setContent { DtcScreen(DtcUiState(items = items), onRead = {}, onClear = { cleared++ }, onAskAi = { _, _ -> }) }
        rule.onNodeWithText("Сбросить").performClick()
        rule.onNodeWithText("Сбросить ошибки?").assertExists()
        rule.onNodeWithText("Отмена").performClick()
        assertEquals(0, cleared)
        rule.onNodeWithText("Сбросить").performClick()
        rule.onNode(hasText("Сбросить") and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        assertEquals(1, cleared)
    }

    @Test
    fun clearDisabledWhenNoCodes() {
        rule.setContent { DtcScreen(DtcUiState(), onRead = {}, onClear = {}, onAskAi = { _, _ -> }) }
        rule.onNodeWithText("Сбросить").assertIsNotEnabled()
        rule.onNodeWithText("Ошибок нет", substring = true).assertDoesNotExist()
    }

    @Test
    fun emptyCheckedListSaysNoErrors() {
        rule.setContent { DtcScreen(DtcUiState(checked = true), onRead = {}, onClear = {}, onAskAi = { _, _ -> }) }
        rule.onNodeWithText("Ошибок нет", substring = true).assertExists()
    }
}

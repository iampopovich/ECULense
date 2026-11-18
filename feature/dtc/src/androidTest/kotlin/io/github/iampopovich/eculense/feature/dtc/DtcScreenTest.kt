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
        DtcItem(Dtc("P0300", DtcKind.STORED), "Random/Multiple Cylinder Misfire Detected"),
        DtcItem(Dtc("P1234", DtcKind.PENDING), null),
    )

    @Test
    fun showsCodesKindsAndDescriptions() {
        rule.setContent { DtcScreen(DtcUiState(items = items), onRead = {}, onClear = {}, onAskAi = {}) }
        rule.onNodeWithText("P0300 · сохранённая").assertExists()
        rule.onNodeWithText("Random/Multiple Cylinder Misfire Detected").assertExists()
        rule.onNodeWithText("P1234 · ожидающая").assertExists()
        rule.onNodeWithText("Нет описания в базе (возможно, заводской код)").assertExists()
    }

    @Test
    fun askAiHiddenWithoutHandlerAndShownWithIt() {
        val state = mutableStateOf(DtcUiState(items = items, canAskAi = false))
        rule.setContent { DtcScreen(state.value, onRead = {}, onClear = {}, onAskAi = {}) }
        assertEquals(0, rule.onAllNodesWithText("Спросить ИИ").fetchSemanticsNodes().size)
        state.value = DtcUiState(items = items, canAskAi = true)
        rule.waitForIdle()
        assertEquals(2, rule.onAllNodesWithText("Спросить ИИ").fetchSemanticsNodes().size)
    }

    @Test
    fun clearAsksForConfirmation() {
        var cleared = 0
        rule.setContent { DtcScreen(DtcUiState(items = items), onRead = {}, onClear = { cleared++ }, onAskAi = {}) }
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
        rule.setContent { DtcScreen(DtcUiState(), onRead = {}, onClear = {}, onAskAi = {}) }
        rule.onNodeWithText("Сбросить").assertIsNotEnabled()
    }
}

package io.github.iampopovich.eculense.feature.aichat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iampopovich.eculense.core.ai.ChatMessage
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import io.github.iampopovich.eculense.core.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    private fun user(text: String) = ChatMessage(ChatMessage.Role.USER, text)
    private fun assistant(text: String) = ChatMessage(ChatMessage.Role.ASSISTANT, text)

    @Test
    fun assistantMarkdownIsRenderedWithoutRawSyntax() {
        val answer = "## Причины\n\n- **Подсос воздуха**\n- датчик MAF\n\nПроверьте `STFT`."
        rule.setContent { ChatScreen(ChatUiState(listOf(user("**P0171?**"), assistant(answer))), onSend = {}, onOpenSettings = {}) }

        rule.waitUntil(5_000) { rule.onAllNodesWithText("Причины").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("## Причины", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Подсос воздуха", substring = true).assertExists()
        rule.onNodeWithText("**Подсос", substring = true).assertDoesNotExist()
        // The user message is plain text; asterisks stay as they are.
        rule.onNodeWithText("**P0171?**").assertExists()
        rule.onNodeWithContentDescription(str(R.string.aichat_copy)).assertExists()
    }

    @Test
    fun composerSendIsDisabledUntilTextIsTyped() {
        val sent = mutableListOf<String>()
        rule.setContent { ChatScreen(ChatUiState(), onSend = { sent += it }, onOpenSettings = {}) }
        rule.onNodeWithContentDescription(str(R.string.aichat_send)).assertIsNotEnabled()
        rule.onNodeWithTag("chat-input").performTextInput("P0171?")
        rule.onNodeWithContentDescription(str(R.string.aichat_send)).assertIsEnabled().performClick()
        assertEquals(listOf("P0171?"), sent)
        // field is cleared, the button is disabled again
        rule.onNodeWithContentDescription(str(R.string.aichat_send)).assertIsNotEnabled()
    }

    @Test
    fun streamingTurnsSendIntoStopThatCancels() {
        var stops = 0
        val waiting = ChatUiState(listOf(user("hi"), assistant("")), streaming = true)
        rule.setContent { ChatScreen(waiting, onSend = {}, onOpenSettings = {}, onStop = { stops++ }) }
        rule.onNodeWithContentDescription(str(R.string.aichat_typing)).assertExists()
        rule.onNodeWithContentDescription(str(R.string.aichat_send)).assertDoesNotExist()
        rule.onNodeWithContentDescription(str(R.string.aichat_stop)).assertIsEnabled().performClick()
        assertEquals(1, stops)
    }

    @Test
    fun suggestionFillsTheInputWithoutSending() {
        val sent = mutableListOf<String>()
        rule.setContent { ChatScreen(ChatUiState(), onSend = { sent += it }, onOpenSettings = {}) }
        rule.onNodeWithText(str(R.string.aichat_suggestion_errors)).performClick()
        rule.onNodeWithTag("chat-input").assertTextEquals(str(R.string.aichat_suggestion_errors))
        assertEquals(emptyList<String>(), sent)
        rule.onNodeWithContentDescription(str(R.string.aichat_send)).assertIsEnabled()
    }

    @Test
    fun emptyChatOffersFourSuggestionsIncludingHalfAYear() {
        rule.setContent { ChatScreen(ChatUiState(), onSend = {}, onOpenSettings = {}) }
        listOf(str(R.string.aichat_suggestion_half_year), str(R.string.aichat_suggestion_errors), str(R.string.aichat_suggestion_fuel), str(R.string.aichat_suggestion_service))
            .forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText(str(R.string.aichat_suggestion_half_year)).performClick()
        rule.onNodeWithTag("chat-input").assertTextEquals(str(R.string.aichat_suggestion_half_year))
    }

    @Test
    fun expertsLineAndCollapsedNotesUnderTheAnswer() {
        val notes = listOf(
            io.github.iampopovich.eculense.core.ai.ExpertNote("usage", str(R.string.aichat_role_usage), "- **Поездок** стало 12 в месяц"),
            io.github.iampopovich.eculense.core.ai.ExpertNote("engine", "legacy title", "legacy text", failed = true),
        )
        val state = ChatUiState(
            listOf(user(str(R.string.aichat_suggestion_half_year)), assistant("Коротко: всё ровно")),
            experts = mapOf(1 to ExpertsInfo(listOf(R.string.aichat_role_usage, R.string.aichat_role_engine, R.string.aichat_role_diagnostician).map { UiText.Res(it) }, notes)),
        )
        rule.setContent { ChatScreen(state, onSend = {}, onOpenSettings = {}) }
        val roles = listOf(R.string.aichat_role_usage, R.string.aichat_role_engine, R.string.aichat_role_diagnostician).joinToString(" · ") { str(it) }
        rule.onNodeWithText(str(R.string.aichat_worked_on_answer, roles)).assertExists()
        // collapsed by default
        rule.onNodeWithText("Поездок", substring = true).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.aichat_show_notes, 2)).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Поездок", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("**Поездок", substring = true).assertDoesNotExist()
        // a failed note is our own text: localized and titled by the role id, not by the legacy strings
        val unavailable = str(R.string.aichat_expert_unavailable, str(R.string.aichat_role_engine))
        rule.onNodeWithText(unavailable).assertExists()
        rule.onNodeWithText("legacy text").assertDoesNotExist()
        rule.onNodeWithText(str(R.string.aichat_hide_notes)).performClick()
        rule.onNodeWithText(unavailable).assertDoesNotExist()
    }

    @Test
    fun answerWithoutExpertsHasNoExpertsLine() {
        rule.setContent { ChatScreen(ChatUiState(listOf(user("hi"), assistant("Привет!"))), onSend = {}, onOpenSettings = {}) }
        rule.onNodeWithText(str(R.string.aichat_worked_on_answer, ""), substring = true).assertDoesNotExist()
    }

    @Test
    fun htmlEntitiesInAnswersAreDecoded() {
        rule.setContent {
            ChatScreen(ChatUiState(listOf(user("t"), assistant("Температура ОЖ стабильно &gt;105 °C"))), onSend = {}, onOpenSettings = {})
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText(">105", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("&gt;", substring = true).assertDoesNotExist()
    }

    @Test
    fun stoppedAnswerIsLabelledAndErrorOffersRetry() {
        var retries = 0
        rule.setContent {
            ChatScreen(
                ChatUiState(listOf(user("a"), assistant("част"), user("b")), error = UiText.Raw("Нет связи"), stoppedIndex = 1),
                onSend = {}, onOpenSettings = {}, onRetry = { retries++ },
            )
        }
        rule.onNodeWithText(str(R.string.aichat_answer_stopped)).assertExists()
        rule.onNodeWithText(str(R.string.aichat_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun carDataToggleAndToolProgress() {
        val toggles = mutableListOf<Boolean>()
        val state = mutableStateOf(ChatUiState(carDataAvailable = true, useCarData = true))
        rule.setContent { ChatScreen(state.value, onSend = {}, onOpenSettings = {}, onUseCarData = { toggles += it }) }
        rule.onNodeWithText(str(R.string.aichat_empty_with_data)).assertExists()
        rule.onNodeWithText(str(R.string.aichat_use_car_data)).performClick()
        assertEquals(listOf(false), toggles)

        state.value = ChatUiState(listOf(user(str(R.string.aichat_suggestion_errors)), assistant("")), streaming = true, carDataAvailable = true, useCarData = true, toolStatus = UiText.Res(R.string.aichat_tool_dtc_history, listOf(UiText.Res(R.string.aichat_subject_ai))))
        val looking = str(R.string.aichat_tool_dtc_history, str(R.string.aichat_subject_ai))
        rule.onNodeWithText(looking).assertExists()
        rule.onNodeWithContentDescription(looking).assertExists()
    }

    @Test
    fun noToggleWithoutToolsModule() {
        rule.setContent { ChatScreen(ChatUiState(carDataAvailable = false), onSend = {}, onOpenSettings = {}) }
        rule.onNodeWithText(str(R.string.aichat_use_car_data)).assertDoesNotExist()
    }

    @Test
    fun consentDialogNamesProviderAndDataAndRoutesButtons() {
        val calls = mutableListOf<String>()
        val request = ConsentRequest(io.github.iampopovich.eculense.core.ai.ProviderInfo("DEEPSEEK", "DeepSeek", "deepseek-chat"), str(R.string.aichat_suggestion_errors))
        rule.setContent {
            ChatScreen(
                ChatUiState(carDataAvailable = true, useCarData = true, consent = request),
                onSend = {}, onOpenSettings = {},
                onConsentAccept = { calls += "accept" }, onConsentDecline = { calls += "decline" }, onConsentCancel = { calls += "cancel" },
            )
        }
        rule.onNodeWithText(str(R.string.aichat_consent_title)).assertExists()
        rule.onNodeWithText(str(R.string.aichat_consent_where, "DeepSeek", "deepseek-chat")).assertExists()
        rule.onNodeWithText(str(R.string.aichat_consent_not_sent)).assertExists()
        SHARED_DATA.forEach { rule.onNodeWithText("• " + str(it)).assertExists() }
        rule.onNodeWithText(str(R.string.aichat_consent_allow)).performClick()
        rule.onNodeWithText(str(R.string.aichat_consent_decline)).performClick()
        assertEquals(listOf("accept", "decline"), calls)
    }

    @Test
    fun draftReturnsToTheInput() {
        var consumed = 0
        rule.setContent { ChatScreen(ChatUiState(draft = "вопрос"), onSend = {}, onOpenSettings = {}, onDraftConsumed = { consumed++ }) }
        rule.onNodeWithTag("chat-input").assertTextEquals("вопрос")
        assertEquals(1, consumed)
    }

    @Test
    fun newDialogAsksForConfirmation() {
        var created = 0
        rule.setContent { ChatScreen(ChatUiState(listOf(user("q"), assistant("a"))), onSend = {}, onOpenSettings = {}, onNewDialog = { created++ }) }
        rule.onNodeWithContentDescription(str(R.string.aichat_new_dialog)).performClick()
        rule.onNodeWithText(str(R.string.aichat_new_dialog_title)).assertExists()
        rule.onNodeWithText(str(CoreUiR.string.common_cancel)).performClick()
        assertEquals(0, created)
        rule.onNodeWithContentDescription(str(R.string.aichat_new_dialog)).performClick()
        rule.onNodeWithText(str(R.string.aichat_new_dialog)).performClick()
        assertEquals(1, created)
    }

    @Test
    fun newDialogDisabledForEmptyChat() {
        rule.setContent { ChatScreen(ChatUiState(), onSend = {}, onOpenSettings = {}) }
        rule.onNodeWithContentDescription(str(R.string.aichat_new_dialog)).assertIsNotEnabled()
    }

    @Test
    fun streamingAnswerGrowsAndGetsCopyButtonWhenDone() {
        val state = mutableStateOf(ChatUiState(listOf(user("hi"), assistant("# Заго")), streaming = true))
        rule.setContent { ChatScreen(state.value, onSend = {}, onOpenSettings = {}) }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Заго", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(str(R.string.aichat_copy)).assertDoesNotExist()

        state.value = ChatUiState(listOf(user("hi"), assistant("# Заголовок\n\n1. первый шаг")), streaming = false)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("первый шаг", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Заголовок").assertExists()
        rule.onNodeWithContentDescription(str(R.string.aichat_copy)).assertExists()
    }
}

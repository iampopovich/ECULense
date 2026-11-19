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
import io.github.iampopovich.eculense.core.ai.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatScreenTest {
    @get:Rule
    val rule = createComposeRule()

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
        rule.onNodeWithContentDescription("Копировать ответ").assertExists()
    }

    @Test
    fun composerSendIsDisabledUntilTextIsTyped() {
        val sent = mutableListOf<String>()
        rule.setContent { ChatScreen(ChatUiState(), onSend = { sent += it }, onOpenSettings = {}) }
        rule.onNodeWithContentDescription("Отправить").assertIsNotEnabled()
        rule.onNodeWithTag("chat-input").performTextInput("P0171?")
        rule.onNodeWithContentDescription("Отправить").assertIsEnabled().performClick()
        assertEquals(listOf("P0171?"), sent)
        // field is cleared, the button is disabled again
        rule.onNodeWithContentDescription("Отправить").assertIsNotEnabled()
    }

    @Test
    fun streamingTurnsSendIntoStopThatCancels() {
        var stops = 0
        val waiting = ChatUiState(listOf(user("hi"), assistant("")), streaming = true)
        rule.setContent { ChatScreen(waiting, onSend = {}, onOpenSettings = {}, onStop = { stops++ }) }
        rule.onNodeWithContentDescription("ИИ печатает").assertExists()
        rule.onNodeWithContentDescription("Отправить").assertDoesNotExist()
        rule.onNodeWithContentDescription("Остановить ответ").assertIsEnabled().performClick()
        assertEquals(1, stops)
    }

    @Test
    fun suggestionFillsTheInputWithoutSending() {
        val sent = mutableListOf<String>()
        rule.setContent { ChatScreen(ChatUiState(), onSend = { sent += it }, onOpenSettings = {}) }
        rule.onNodeWithText("Объясни мои ошибки").performClick()
        rule.onNodeWithTag("chat-input").assertTextEquals("Объясни мои ошибки")
        assertEquals(emptyList<String>(), sent)
        rule.onNodeWithContentDescription("Отправить").assertIsEnabled()
    }

    @Test
    fun emptyChatOffersFourSuggestionsIncludingHalfAYear() {
        rule.setContent { ChatScreen(ChatUiState(), onSend = {}, onOpenSettings = {}) }
        listOf("Как машина вела себя за полгода", "Объясни мои ошибки", "Сколько трачу на топливо", "Что пора обслужить")
            .forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("Как машина вела себя за полгода").performClick()
        rule.onNodeWithTag("chat-input").assertTextEquals("Как машина вела себя за полгода")
    }

    @Test
    fun expertsLineAndCollapsedNotesUnderTheAnswer() {
        val notes = listOf(
            io.github.iampopovich.eculense.core.ai.ExpertNote("usage", "Аналитик эксплуатации", "- **Поездок** стало 12 в месяц"),
            io.github.iampopovich.eculense.core.ai.ExpertNote("engine", "Моторист", "Эксперт «Моторист» недоступен", failed = true),
        )
        val state = ChatUiState(
            listOf(user("Как машина вела себя за полгода"), assistant("Коротко: всё ровно")),
            experts = mapOf(1 to ExpertsInfo(listOf("Аналитик эксплуатации", "Моторист", "Диагност"), notes)),
        )
        rule.setContent { ChatScreen(state, onSend = {}, onOpenSettings = {}) }
        rule.onNodeWithText("Над ответом работали: Аналитик эксплуатации · Моторист · Диагност").assertExists()
        // collapsed by default
        rule.onNodeWithText("Поездок", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Заметки экспертов (2)").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Поездок", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("**Поездок", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Эксперт «Моторист» недоступен").assertExists()
        rule.onNodeWithText("Скрыть заметки экспертов").performClick()
        rule.onNodeWithText("Эксперт «Моторист» недоступен").assertDoesNotExist()
    }

    @Test
    fun answerWithoutExpertsHasNoExpertsLine() {
        rule.setContent { ChatScreen(ChatUiState(listOf(user("hi"), assistant("Привет!"))), onSend = {}, onOpenSettings = {}) }
        rule.onNodeWithText("Над ответом работали", substring = true).assertDoesNotExist()
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
                ChatUiState(listOf(user("a"), assistant("част"), user("b")), error = "Нет связи", stoppedIndex = 1),
                onSend = {}, onOpenSettings = {}, onRetry = { retries++ },
            )
        }
        rule.onNodeWithText("Ответ остановлен").assertExists()
        rule.onNodeWithText("Повторить").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun carDataToggleAndToolProgress() {
        val toggles = mutableListOf<Boolean>()
        val state = mutableStateOf(ChatUiState(carDataAvailable = true, useCarData = true))
        rule.setContent { ChatScreen(state.value, onSend = {}, onOpenSettings = {}, onUseCarData = { toggles += it }) }
        rule.onNodeWithText("без VIN и координат", substring = true).assertExists()
        rule.onNodeWithText("Использовать данные моей машины").performClick()
        assertEquals(listOf(false), toggles)

        state.value = ChatUiState(listOf(user("Объясни мои ошибки"), assistant("")), streaming = true, carDataAvailable = true, useCarData = true, toolStatus = "ИИ смотрит ошибки…")
        rule.onNodeWithText("ИИ смотрит ошибки…").assertExists()
        rule.onNodeWithContentDescription("ИИ смотрит ошибки…").assertExists()
    }

    @Test
    fun noToggleWithoutToolsModule() {
        rule.setContent { ChatScreen(ChatUiState(carDataAvailable = false), onSend = {}, onOpenSettings = {}) }
        rule.onNodeWithText("Использовать данные моей машины").assertDoesNotExist()
    }

    @Test
    fun consentDialogNamesProviderAndDataAndRoutesButtons() {
        val calls = mutableListOf<String>()
        val request = ConsentRequest(io.github.iampopovich.eculense.core.ai.ProviderInfo("DEEPSEEK", "DeepSeek", "deepseek-chat"), "Объясни мои ошибки")
        rule.setContent {
            ChatScreen(
                ChatUiState(carDataAvailable = true, useCarData = true, consent = request),
                onSend = {}, onOpenSettings = {},
                onConsentAccept = { calls += "accept" }, onConsentDecline = { calls += "decline" }, onConsentCancel = { calls += "cancel" },
            )
        }
        rule.onNodeWithText("Отправить данные машины ИИ?").assertExists()
        rule.onNodeWithText("«DeepSeek» (модель deepseek-chat)", substring = true).assertExists()
        rule.onNodeWithText("VIN, координаты GPS и API-ключ не передаются", substring = true).assertExists()
        SHARED_DATA.forEach { rule.onNodeWithText("• $it").assertExists() }
        rule.onNodeWithText("Разрешить").performClick()
        rule.onNodeWithText("Без данных машины").performClick()
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
        rule.onNodeWithContentDescription("Новый диалог").performClick()
        rule.onNodeWithText("Начать новый диалог?").assertExists()
        rule.onNodeWithText("Отмена").performClick()
        assertEquals(0, created)
        rule.onNodeWithContentDescription("Новый диалог").performClick()
        rule.onNodeWithText("Новый диалог").performClick()
        assertEquals(1, created)
    }

    @Test
    fun newDialogDisabledForEmptyChat() {
        rule.setContent { ChatScreen(ChatUiState(), onSend = {}, onOpenSettings = {}) }
        rule.onNodeWithContentDescription("Новый диалог").assertIsNotEnabled()
    }

    @Test
    fun streamingAnswerGrowsAndGetsCopyButtonWhenDone() {
        val state = mutableStateOf(ChatUiState(listOf(user("hi"), assistant("# Заго")), streaming = true))
        rule.setContent { ChatScreen(state.value, onSend = {}, onOpenSettings = {}) }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Заго", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Копировать ответ").assertDoesNotExist()

        state.value = ChatUiState(listOf(user("hi"), assistant("# Заголовок\n\n1. первый шаг")), streaming = false)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("первый шаг", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Заголовок").assertExists()
        rule.onNodeWithContentDescription("Копировать ответ").assertExists()
    }
}

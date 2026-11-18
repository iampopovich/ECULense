package io.github.iampopovich.eculense.feature.aichat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iampopovich.eculense.core.ai.ChatMessage
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
        // Сообщение пользователя — обычный текст, звёздочки остаются как есть.
        rule.onNodeWithText("**P0171?**").assertExists()
        rule.onNodeWithContentDescription("Копировать ответ").assertExists()
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

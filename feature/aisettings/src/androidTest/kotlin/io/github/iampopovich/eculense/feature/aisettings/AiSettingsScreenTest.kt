package io.github.iampopovich.eculense.feature.aisettings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iampopovich.eculense.core.ai.LlmProviderKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiSettingsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun openRouterIsDefaultAndKeyIsMasked() {
        rule.setContent {
            AiSettingsScreen(AiSettingsForm(apiKey = "sk-or-secret"), onProvider = {}, onChange = {}, onSave = {}, onClearKey = {})
        }
        rule.onNodeWithText("OpenRouter").assertIsSelected()
        // Поле ключа: Password-семантика, на экране только точки.
        rule.onNode(hasSetTextAction() and hasText("API-ключ"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("•".repeat("sk-or-secret".length))))
        // У облачного провайдера нет поля адреса.
        rule.onNodeWithText("Адрес сервера").assertDoesNotExist()
    }

    @Test
    fun localServerShowsAddressAndSavedKeyCanBeRemoved() {
        val removed = mutableStateOf(0)
        rule.setContent {
            AiSettingsScreen(
                AiSettingsForm(provider = LlmProviderKind.OLLAMA, hasSavedKey = true),
                onProvider = {}, onChange = {}, onSave = {}, onClearKey = { removed.value++ },
            )
        }
        rule.onNodeWithText("Адрес сервера").assertExists()
        rule.onNodeWithText("Удалить ключ").performClick()
        assertEquals(1, removed.value)
    }
}

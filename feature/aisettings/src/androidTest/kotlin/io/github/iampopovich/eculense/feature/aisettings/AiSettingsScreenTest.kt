package io.github.iampopovich.eculense.feature.aisettings

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iampopovich.eculense.core.ai.LlmProviderKind
import io.github.iampopovich.eculense.core.ai.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiSettingsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val models = listOf(
        ModelInfo("openai/gpt-4.1-mini", "OpenAI: GPT-4.1 Mini", 1_047_576, false, recommended = true),
        ModelInfo("meta-llama/llama-3.3-70b-instruct:free", "Meta: Llama 3.3 70B (free)", 131_072, true),
    )

    @Test
    fun cloudBuildShowsMutedHintInsteadOfOnDeviceEntry() {
        rule.setContent { AiSettingsScreen(AiSettingsUiState(loaded = true, onDeviceInOtherBuild = true), AiSettingsActions()) }
        rule.onNodeWithText("Локальные модели доступны в сборке «Локально»").assertExists()
        rule.onNodeWithText("На устройстве").assertDoesNotExist()
    }

    @Test
    fun localBuildShowsOnDeviceEntryWithoutHint() {
        rule.setContent { AiSettingsScreen(AiSettingsUiState(loaded = true, onDeviceAvailable = true), AiSettingsActions()) }
        rule.onNodeWithText("На устройстве").assertExists()
        rule.onNodeWithText("Локальные модели доступны в сборке «Локально»").assertDoesNotExist()
    }

    @Test
    fun freshWizardLocksModelStepAndMasksKey() {
        rule.setContent { AiSettingsScreen(AiSettingsUiState(loaded = true, apiKey = "sk-or-secret"), AiSettingsActions()) }
        rule.onNodeWithText("OpenRouter").assertIsSelected()
        rule.onNodeWithText("Адрес API").assertDoesNotExist()
        rule.onNode(hasSetTextAction() and hasText("API-ключ"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("•".repeat("sk-or-secret".length))))
        rule.onNodeWithText("Сначала проверьте подключение", substring = true).assertExists()
        rule.onNodeWithText("Поиск модели").assertDoesNotExist()
        rule.onNodeWithText("Сохранить").assertIsNotEnabled()
    }

    @Test
    fun failedCheckShowsReasonAndKeepsModelStepLocked() {
        val failed = CheckState.Failed("Доступ запрещён (403): … Включите VPN и проверьте снова.")
        rule.setContent { AiSettingsScreen(AiSettingsUiState(loaded = true, check = failed), AiSettingsActions()) }
        rule.onNodeWithText("Включите VPN", substring = true).assertExists()
        rule.onNodeWithText("Поиск модели").assertDoesNotExist()
        rule.onNodeWithText("Сохранить").assertIsNotEnabled()
    }

    @Test
    fun localUrlErrorIsShownUnderTheField() {
        rule.setContent {
            AiSettingsScreen(
                AiSettingsUiState(loaded = true, provider = LlmProviderKind.OTHER, baseUrl = "garbage", urlError = "Адрес должен начинаться с http:// или https://"),
                AiSettingsActions(),
            )
        }
        rule.onNodeWithText("Адрес должен начинаться с http:// или https://").assertExists()
    }

    @Test
    fun passedCheckShowsCatalogAndModelPickEnablesSave() {
        val picked = mutableListOf<String>()
        val passed = CheckState.Passed(models, listed = true, message = "Подключение работает: 2 модели")
        rule.setContent {
            AiSettingsScreen(AiSettingsUiState(loaded = true, check = passed), AiSettingsActions(onModel = { picked += it }))
        }
        rule.onNodeWithText("✓ Подключение работает: 2 модели").assertExists()
        // Eight providers push the catalog below the screen; LazyColumn composes rows only after scrolling.
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("бесплатно", substring = true))
        rule.onNodeWithText("Поиск модели").assertExists()
        rule.onNodeWithText("бесплатно", substring = true).assertExists()
        rule.onNodeWithText("★ рекомендуем", substring = true).assertExists()
        rule.onNodeWithText("Сохранить").assertIsNotEnabled()
        rule.onNodeWithText("Meta: Llama 3.3 70B (free)").performClick()
        assertEquals(listOf("meta-llama/llama-3.3-70b-instruct:free"), picked)
    }

    @Test
    fun chosenModelEnablesSave() {
        val passed = CheckState.Passed(models, listed = true, message = "ok")
        rule.setContent {
            AiSettingsScreen(AiSettingsUiState(loaded = true, check = passed, model = "openai/gpt-4.1-mini"), AiSettingsActions())
        }
        rule.onNodeWithText("Сохранить").assertIsEnabled()
        rule.onNodeWithText("Модель: openai/gpt-4.1-mini").assertExists()
    }

    @Test
    fun savedConfigShowsSummaryWithEditAndRemoveKey() {
        val removed = mutableIntStateOf(0)
        val edited = mutableIntStateOf(0)
        val saved = SavedConfig(LlmProviderKind.OPENROUTER, "openai/gpt-4.1-mini", LlmProviderKind.OPENROUTER.defaultBaseUrl, hasKey = true)
        rule.setContent {
            AiSettingsScreen(
                AiSettingsUiState(loaded = true, saved = saved, editing = false),
                AiSettingsActions(onClearKey = { removed.intValue++ }, onEdit = { edited.intValue++ }),
            )
        }
        rule.onNodeWithText("OpenRouter · openai/gpt-4.1-mini · ключ сохранён").assertExists()
        rule.onNodeWithText("Удалить ключ").performClick()
        rule.onNodeWithText("Изменить").performClick()
        assertEquals(1, removed.intValue)
        assertEquals(1, edited.intValue)
    }

    @Test
    fun customServerWithoutListAllowsTypedModelId() {
        val passed = CheckState.Passed(emptyList(), listed = false, message = "manual")
        rule.setContent {
            AiSettingsScreen(
                AiSettingsUiState(loaded = true, provider = LlmProviderKind.OTHER, baseUrl = "http://127.0.0.1:8089/v1", check = passed),
                AiSettingsActions(),
            )
        }
        rule.onNodeWithText("API-ключ (если нужен)").assertExists()
        rule.onNodeWithText("ID модели").assertExists()
        rule.onNodeWithText("Поиск модели").assertDoesNotExist()
    }

    @Test
    fun providerListIsKoogPresetsPlusOther() {
        rule.setContent { AiSettingsScreen(AiSettingsUiState(loaded = true), AiSettingsActions()) }
        listOf("OpenRouter", "OpenAI", "Anthropic", "Google Gemini", "DeepSeek", "Mistral", "Ollama", "Другой (свой сервер)")
            .forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("Свой сервер", substring = true).assertDoesNotExist()
    }

    @Test
    fun ollamaHasNeitherUrlNorKeyField() {
        rule.setContent {
            AiSettingsScreen(AiSettingsUiState(loaded = true, provider = LlmProviderKind.OLLAMA), AiSettingsActions())
        }
        rule.onNodeWithText("Ollama").assertIsSelected()
        rule.onNodeWithText("Адрес API").assertDoesNotExist()
        rule.onNodeWithText("API-ключ", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Проверить подключение").assertIsEnabled()
    }

    @Test
    fun otherShowsUrlAndOptionalKey() {
        rule.setContent {
            AiSettingsScreen(AiSettingsUiState(loaded = true, provider = LlmProviderKind.OTHER), AiSettingsActions())
        }
        rule.onNode(hasSetTextAction() and hasText("Адрес API")).assertExists()
        rule.onNodeWithText("API-ключ (если нужен)").assertExists()
    }
}

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
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iampopovich.eculense.core.ai.LlmProviderKind
import io.github.iampopovich.eculense.core.ai.ModelInfo
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import io.github.iampopovich.eculense.core.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiSettingsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    private val models = listOf(
        ModelInfo("openai/gpt-4.1-mini", "OpenAI: GPT-4.1 Mini", 1_047_576, false, recommended = true),
        ModelInfo("meta-llama/llama-3.3-70b-instruct:free", "Meta: Llama 3.3 70B (free)", 131_072, true),
    )

    @Test
    fun cloudBuildShowsMutedHintInsteadOfOnDeviceEntry() {
        rule.setContent { AiSettingsScreen(AiSettingsUiState(loaded = true, onDeviceInOtherBuild = true), AiSettingsActions()) }
        rule.onNodeWithText(str(R.string.aisettings_on_device_other_build)).assertExists()
        rule.onNodeWithText(str(R.string.aisettings_on_device)).assertDoesNotExist()
    }

    @Test
    fun localBuildShowsOnDeviceEntryWithoutHint() {
        rule.setContent { AiSettingsScreen(AiSettingsUiState(loaded = true, onDeviceAvailable = true), AiSettingsActions()) }
        rule.onNodeWithText(str(R.string.aisettings_on_device)).assertExists()
        rule.onNodeWithText(str(R.string.aisettings_on_device_other_build)).assertDoesNotExist()
    }

    @Test
    fun freshWizardLocksModelStepAndMasksKey() {
        rule.setContent { AiSettingsScreen(AiSettingsUiState(loaded = true, apiKey = "sk-or-secret"), AiSettingsActions()) }
        rule.onNodeWithText("OpenRouter").assertIsSelected()
        rule.onNodeWithText(str(R.string.aisettings_api_url)).assertDoesNotExist()
        rule.onNode(hasSetTextAction() and hasText(str(R.string.aisettings_api_key)))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("•".repeat("sk-or-secret".length))))
        rule.onNodeWithText(str(R.string.aisettings_model_locked)).assertExists()
        rule.onNodeWithText(str(R.string.aisettings_model_search)).assertDoesNotExist()
        rule.onNodeWithText(str(CoreUiR.string.common_save)).assertIsNotEnabled()
    }

    @Test
    fun failedCheckShowsReasonAndKeepsModelStepLocked() {
        val failed = CheckState.Failed(UiText.Res(R.string.aisettings_err_403))
        rule.setContent { AiSettingsScreen(AiSettingsUiState(loaded = true, check = failed), AiSettingsActions()) }
        rule.onNodeWithText(str(R.string.aisettings_err_403)).assertExists()
        rule.onNodeWithText(str(R.string.aisettings_model_search)).assertDoesNotExist()
        rule.onNodeWithText(str(CoreUiR.string.common_save)).assertIsNotEnabled()
    }

    @Test
    fun localUrlErrorIsShownUnderTheField() {
        rule.setContent {
            AiSettingsScreen(
                AiSettingsUiState(loaded = true, provider = LlmProviderKind.OTHER, baseUrl = "garbage", urlError = UiText.Res(R.string.aisettings_url_scheme)),
                AiSettingsActions(),
            )
        }
        rule.onNodeWithText(str(R.string.aisettings_url_scheme)).assertExists()
    }

    @Test
    fun passedCheckShowsCatalogAndModelPickEnablesSave() {
        val picked = mutableListOf<String>()
        val passed = CheckState.Passed(models, listed = true, message = UiText.Plural(R.plurals.aisettings_check_ok, 2))
        rule.setContent {
            AiSettingsScreen(AiSettingsUiState(loaded = true, check = passed), AiSettingsActions(onModel = { picked += it }))
        }
        rule.onNodeWithText("✓ " + ctx.resources.getQuantityString(R.plurals.aisettings_check_ok, 2, 2)).assertExists()
        // Eight providers push the catalog below the screen; LazyColumn composes rows only after scrolling.
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(" · " + str(R.string.aisettings_free), substring = true))
        rule.onNodeWithText(str(R.string.aisettings_model_search)).assertExists()
        rule.onNodeWithText(" · " + str(R.string.aisettings_free), substring = true).assertExists()
        rule.onNodeWithText(str(R.string.aisettings_recommended), substring = true).assertExists()
        rule.onNodeWithText(str(CoreUiR.string.common_save)).assertIsNotEnabled()
        rule.onNodeWithText("Meta: Llama 3.3 70B (free)").performClick()
        assertEquals(listOf("meta-llama/llama-3.3-70b-instruct:free"), picked)
    }

    @Test
    fun chosenModelEnablesSave() {
        val passed = CheckState.Passed(models, listed = true, message = UiText.Raw("ok"))
        rule.setContent {
            AiSettingsScreen(AiSettingsUiState(loaded = true, check = passed, model = "openai/gpt-4.1-mini"), AiSettingsActions())
        }
        rule.onNodeWithText(str(CoreUiR.string.common_save)).assertIsEnabled()
        rule.onNodeWithText(str(R.string.aisettings_model_selected, "openai/gpt-4.1-mini")).assertExists()
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
        rule.onNodeWithText("OpenRouter · openai/gpt-4.1-mini · " + str(R.string.aisettings_key_saved)).assertExists()
        rule.onNodeWithText(str(R.string.aisettings_clear_key)).performClick()
        rule.onNodeWithText(str(CoreUiR.string.common_edit)).performClick()
        assertEquals(1, removed.intValue)
        assertEquals(1, edited.intValue)
    }

    @Test
    fun customServerWithoutListAllowsTypedModelId() {
        val passed = CheckState.Passed(emptyList(), listed = false, message = UiText.Raw("manual"))
        rule.setContent {
            AiSettingsScreen(
                AiSettingsUiState(loaded = true, provider = LlmProviderKind.OTHER, baseUrl = "http://127.0.0.1:8089/v1", check = passed),
                AiSettingsActions(),
            )
        }
        rule.onNodeWithText(str(R.string.aisettings_api_key_optional)).assertExists()
        rule.onNodeWithText(str(R.string.aisettings_model_id)).assertExists()
        rule.onNodeWithText(str(R.string.aisettings_model_search)).assertDoesNotExist()
    }

    @Test
    fun providerListIsKoogPresetsPlusOther() {
        rule.setContent { AiSettingsScreen(AiSettingsUiState(loaded = true), AiSettingsActions()) }
        listOf("OpenRouter", "OpenAI", "Anthropic", "Google Gemini", "DeepSeek", "Mistral", "Ollama", str(R.string.aisettings_provider_other))
            .forEach { rule.onNodeWithText(it).assertExists() }
    }

    @Test
    fun ollamaHasNeitherUrlNorKeyField() {
        rule.setContent {
            AiSettingsScreen(AiSettingsUiState(loaded = true, provider = LlmProviderKind.OLLAMA), AiSettingsActions())
        }
        rule.onNodeWithText("Ollama").assertIsSelected()
        rule.onNodeWithText(str(R.string.aisettings_api_url)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.aisettings_api_key), substring = true).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.aisettings_check)).assertIsEnabled()
    }

    @Test
    fun otherShowsUrlAndOptionalKey() {
        rule.setContent {
            AiSettingsScreen(AiSettingsUiState(loaded = true, provider = LlmProviderKind.OTHER), AiSettingsActions())
        }
        rule.onNode(hasSetTextAction() and hasText(str(R.string.aisettings_api_url))).assertExists()
        rule.onNodeWithText(str(R.string.aisettings_api_key_optional)).assertExists()
    }
}

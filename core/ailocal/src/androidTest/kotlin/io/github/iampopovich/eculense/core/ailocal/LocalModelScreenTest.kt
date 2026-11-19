package io.github.iampopovich.eculense.core.ailocal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalModelScreenTest {
    @get:Rule val rule = createComposeRule()

    private val gb = 1L shl 30
    private val device = DeviceProfile(
        totalRamBytes = 11 * gb, availRamBytes = 3 * gb, socManufacturer = "Тестовая", socModel = "T1",
        abis = listOf("arm64-v8a"), freeStorageBytes = 300 * gb, gpu = true, npu = null, aicore = true, sdk = 36,
    )
    private val catalog = LocalModelCatalog.merge(LocalModelCatalog.koogEntries(), GalleryAllowlist.toLocalModels(GalleryAllowlist.bundled()), emptyList())
    private val e2b = catalog.first { it.fileName == "gemma-4-E2B-it.litertlm" }
    private val e4b = catalog.first { it.fileName == "gemma-4-E4B-it.litertlm" }

    private fun row(m: LocalModel, installed: Boolean = false, progress: Int? = null, report: BenchReport? = null, active: BackendKind? = null) =
        ModelRow(m, FitEstimator.estimate(m.sizeBytes, device, installed, m.minRamGb), installed, progress = progress, downloadedBytes = (progress ?: 0) * 26_000_000L, report = report, activeBackend = active)

    private val rows get() = catalog.map { row(it) }

    private val report = BenchReport(
        e2b.packId,
        listOf(
            BackendRun(BackendKind.CPU, ok = true, initSeconds = 2.6, ttftSeconds = 11.3, prefillTokensPerSecond = 22.7, decodeTokensPerSecond = 11.4, peakRssBytes = 2 * gb),
            BackendRun(BackendKind.GPU, ok = true, initSeconds = 26.3, ttftSeconds = 1.2, prefillTokensPerSecond = 230.2, decodeTokensPerSecond = 13.2, peakRssBytes = 2 * gb),
            BackendRun(BackendKind.NPU, ok = false, error = "процесс теста завершился аварийно"),
        ),
        1L,
    )

    @Test
    fun compactListShowsOneRowPerModelWithBadgesAndDownloadIcon() {
        var asked: String? = null
        rule.setContent {
            LocalModelScreen(
                LocalModelUiState(device = device, nano = NanoStatus(false, "Gemini Nano недоступен: тестовая причина."), rows = rows),
                LocalModelActions(onDownload = { asked = it }),
            )
        }
        rule.onNodeWithText("Ваше устройство").assertIsDisplayed()
        rule.onNodeWithText("Gemini Nano недоступен: тестовая причина.").assertIsDisplayed()
        // smallest first: FunctionGemma (Koog, actions only)
        rule.onNode(hasTestTag("local-model-list")).performScrollToNode(hasTestTag("model-${catalog.first().packId}"))
        rule.onNodeWithText("только действия", useUnmergedTree = true).assertIsDisplayed()
        rule.onNode(hasTestTag("local-model-list")).performScrollToNode(hasContentDescription("Скачать ${e2b.title}"))
        rule.onNodeWithContentDescription("Скачать ${e2b.title}").performClick()
        assertEquals(e2b.packId, asked)
    }

    @Test
    fun tappingARowOpensTheDetailsSheet() {
        var selected: String? by mutableStateOf(null)
        rule.setContent {
            LocalModelScreen(LocalModelUiState(device = device, rows = rows, selected = selected), LocalModelActions(onSelect = { selected = it }))
        }
        rule.onNode(hasTestTag("local-model-list")).performScrollToNode(hasTestTag("model-${e4b.packId}"))
        rule.onNodeWithTag("model-${e4b.packId}").performClick()
        rule.waitForIdle()
        assertEquals(e4b.packId, selected)
        rule.onNodeWithTag("model-sheet").assertExists()
        // Gallery wants 12 GB for E4B → tight on an 11 GB phone
        rule.onNodeWithText("Gallery рекомендует от 12 ГБ ОЗУ").assertExists()
        rule.onNodeWithText("Оценка: Впритык").assertExists()
        rule.onNodeWithText("Ускорители: GPU, CPU").assertExists()
    }

    @Test
    fun confirmDialogWarnsAboutRiskAndMeteredNetwork() {
        var confirmed = false
        rule.setContent {
            LocalModelScreen(
                LocalModelUiState(device = device, rows = listOf(row(e2b)), confirm = DownloadConfirm(e2b.packId, e2b.title, e2b.sizeBytes, FitEstimator.estimate(e2b.sizeBytes, device), metered = true, gatedWithoutToken = false)),
                LocalModelActions(onConfirmDownload = { confirmed = true }),
            )
        }
        rule.onNodeWithText("Скачиваете на свой риск", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Вы на мобильной сети", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Скачать по мобильной сети").performClick()
        assertEquals(true, confirmed)
    }

    @Test
    fun verdictInRowAndNumbersInSheet() {
        rule.setContent {
            LocalModelScreen(LocalModelUiState(device = device, rows = listOf(row(e2b, installed = true, report = report, active = BackendKind.GPU), row(e4b)), selected = e2b.packId), LocalModelActions())
        }
        rule.onNodeWithText("Вердикт: Комфортно").assertExists()
        rule.onNodeWithText("Лучший бэкенд: GPU").assertExists()
        rule.onNodeWithText("GPU: первый токен 1,2 с", substring = true).assertExists()
        rule.onNodeWithText("NPU: не подходит", substring = true).assertExists()
        rule.onNodeWithText("Используется в чате: GPU").assertExists()
    }

    /**
     * Download progress changes between gestures (as on a phone) — finger scrolling still reaches the end.
     * The test drives the progress itself: an infinite loop in LaunchedEffect would keep the test clock from going idle.
     */
    @Test
    fun swipeGesturesReachTheEndWhileADownloadIsProgressing() {
        var pct by mutableIntStateOf(1)
        rule.setContent {
            LocalModelScreen(LocalModelUiState(device = device, rows = rows.map { if (it.model.packId == e2b.packId) row(e2b, progress = pct) else it }), LocalModelActions())
        }
        repeat(15) {
            pct = (pct + 3) % 100
            rule.onNode(hasTestTag("local-model-list")).performTouchInput { swipeUp() }
            rule.waitForIdle()
        }
        rule.onNodeWithText("Сохранить токен").assertIsDisplayed()
    }

    @Test
    fun searchResultsAppearUnderOtherModels() {
        val hf = LocalModel("model-custom-qwen3-0-6b-1", "Qwen3-0.6B.litertlm", "Qwen3-0.6B", "", "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/abc/Qwen3-0.6B.litertlm", 613_722_264L, null, false, ModelOrigin.HF, "apache-2.0", "", "abc", repo = "litert-community/Qwen3-0.6B")
        rule.setContent {
            LocalModelScreen(LocalModelUiState(device = device, rows = rows, searchQuery = "qwen3", searchResults = listOf(row(hf))), LocalModelActions())
        }
        rule.onNode(hasTestTag("local-model-list")).performScrollToNode(hasText("Qwen3-0.6B"))
        rule.onNodeWithText("Qwen3-0.6B").assertIsDisplayed()
    }
}

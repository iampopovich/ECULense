package io.github.iampopovich.eculense.core.ailocal

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
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
    private val e2b = LocalModelCatalog.koogEntries().first { it.fileName == "gemma-4-E2B-it.litertlm" }
    private val e4b = LocalModelCatalog.koogEntries().first { it.fileName == "gemma-4-E4B-it.litertlm" }

    private fun row(m: LocalModel, installed: Boolean = false, progress: Int? = null, report: BenchReport? = null, active: BackendKind? = null) =
        ModelRow(m, FitEstimator.estimate(m.sizeBytes, device, installed), installed, progress = progress, downloadedBytes = (progress ?: 0) * 26_000_000L, report = report, activeBackend = active)

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
    fun deviceCardEstimateAndRiskConfirmation() {
        var asked: String? = null
        rule.setContent {
            LocalModelScreen(
                LocalModelUiState(device = device, nano = NanoStatus(false, "Gemini Nano недоступен: тестовая причина."), rows = listOf(row(e2b), row(e4b))),
                LocalModelActions(onDownload = { asked = it }),
            )
        }
        rule.onNodeWithText("Ваше устройство").assertIsDisplayed()
        rule.onNodeWithText("Gemini Nano недоступен: тестовая причина.").assertIsDisplayed()
        rule.onNode(hasTestTag("local-model-list")).performScrollToNode(hasTestTag("model-${e2b.packId}"))
        rule.onAllNodesWithText("Оценка: Потянет", useUnmergedTree = true)[0].assertIsDisplayed()
        rule.onAllNodesWithText("Скачать", useUnmergedTree = true)[0].performClick()
        assertEquals(e2b.packId, asked)
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
    fun verdictShowsNumbersBestBackendAndFailedOne() {
        rule.setContent {
            LocalModelScreen(LocalModelUiState(device = device, rows = listOf(row(e2b, installed = true, report = report, active = BackendKind.GPU), row(e4b))), LocalModelActions())
        }
        rule.onNode(hasTestTag("local-model-list")).performScrollToNode(hasTestTag("verdict-${e2b.packId}"))
        rule.onNodeWithText("Вердикт: Комфортно").assertIsDisplayed()
        rule.onNodeWithText("Лучший бэкенд: GPU").assertIsDisplayed()
        rule.onNodeWithText("GPU: первый токен 1,2 с", substring = true).assertIsDisplayed()
        rule.onNodeWithText("NPU: не подходит", substring = true).assertExists()
        rule.onNodeWithText("Используется в чате: GPU").assertExists()
    }

    /** Прогресс загрузки обновляет список каждые полсекунды — прокрутка до конца списка всё равно должна работать. */
    @Test
    fun listScrollsToTheTokenCardWhileADownloadIsProgressing() {
        rule.setContent {
            var pct by remember { mutableStateOf(1) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(300)
                    pct = (pct + 1) % 100
                }
            }
            LocalModelScreen(LocalModelUiState(device = device, rows = listOf(row(e2b, progress = pct), row(e4b))), LocalModelActions())
        }
        rule.onNode(hasTestTag("local-model-list")).performScrollToNode(hasText("Сохранить токен"))
        rule.onNodeWithText("Сохранить токен").assertIsDisplayed()
    }

    /** То же жестами пальца (на телефоне прокрутка во время загрузки застревала). */
    @Test
    fun swipeGesturesReachTheEndWhileADownloadIsProgressing() {
        rule.setContent {
            var pct by remember { mutableStateOf(1) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(300)
                    pct = (pct + 1) % 100
                }
            }
            LocalModelScreen(LocalModelUiState(device = device, rows = listOf(row(e2b, progress = pct), row(e4b))), LocalModelActions())
        }
        repeat(12) {
            rule.onNode(hasTestTag("local-model-list")).performTouchInput { swipeUp() }
            rule.waitForIdle()
        }
        rule.onNodeWithText("Сохранить токен").assertIsDisplayed()
    }

    @Test
    fun swipeGesturesReachTheEndWithoutUpdates() {
        rule.setContent {
            LocalModelScreen(LocalModelUiState(device = device, rows = listOf(row(e2b, progress = 5), row(e4b))), LocalModelActions())
        }
        repeat(12) {
            rule.onNode(hasTestTag("local-model-list")).performTouchInput { swipeUp() }
            rule.waitForIdle()
        }
        rule.onNodeWithText("Сохранить токен").assertIsDisplayed()
    }
}

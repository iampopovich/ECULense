package io.github.iampopovich.eculense.feature.datapacks

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iampopovich.eculense.core.datapacks.PackCatalog
import io.github.iampopovich.eculense.core.datapacks.PackState
import io.github.iampopovich.eculense.core.datapacks.PackStore
import io.github.iampopovich.eculense.core.obdb.Ecu
import io.github.iampopovich.eculense.core.obdb.Generation
import io.github.iampopovich.eculense.core.obdb.Signal
import io.github.iampopovich.eculense.core.obdb.SignalCommand
import io.github.iampopovich.eculense.core.obdb.SignalFormat
import io.github.iampopovich.eculense.core.obdb.SignalsetResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DataPacksScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val installed = PackStore.Installed("obdex", "2026-11-07T13:44:03.324Z", File("x"), 41_000_000)
    private val packs = listOf(
        PackState(PackCatalog.vehicles, progress = 42),
        PackState(PackCatalog.obdex, installed = installed, updateVersion = "2026-12-01T00:00:00Z"),
        PackState(PackCatalog.oemDtc, error = "HTTP 404"),
    )

    private fun show(state: DataPacksUiState, calls: MutableList<String> = mutableListOf()) = rule.setContent {
        DataPacksScreen(
            state,
            onDownload = { calls += "download ${it.manifest.id}" }, onUpdate = { calls += "update ${it.manifest.id}" },
            onDelete = { calls += "delete ${it.manifest.id}" }, onCancel = { calls += "cancel ${it.manifest.id}" },
            onConfirm = { calls += "confirm" }, onDismiss = { calls += "dismiss" }, onCheckUpdates = {}, onSignalsets = { calls += "obdb" },
            onAbout = { calls += "about" },
        )
    }

    @Test
    fun packsShowStatusLicenseAndActions() {
        val calls = mutableListOf<String>()
        show(DataPacksUiState(packs = packs), calls)
        rule.onNodeWithText("Справочник моделей и ТТХ").assertExists()
        rule.onNodeWithText("Скачивается 42 %").assertExists()
        rule.onNodeWithText("Лицензия: ODbL 1.0 (данные), MIT (код)").assertExists()
        rule.onNodeWithText("Отменить").performClick()
        rule.onRoot().performScrollToNode(hasText("Версия 2026-11-07T13:44:03.324Z", substring = true))
        rule.onNodeWithText("доступно обновление 2026-12-01T00:00:00Z", substring = true).assertExists()
        rule.onNodeWithText("Обновить").performClick()
        rule.onNodeWithText("Удалить").performClick()
        rule.onRoot().performScrollToNode(hasText("Ошибка: HTTP 404"))
        rule.onRoot().performScrollToNode(hasText("Скачать"))
        rule.onNodeWithText("Скачать").performClick()
        assertEquals(listOf("cancel vehicles", "update obdex", "delete obdex", "download oem-dtc"), calls)
    }

    @Test
    fun meteredDownloadAsksWithSize() {
        val calls = mutableListOf<String>()
        show(DataPacksUiState(packs = packs, confirm = PackConfirm.Metered(PackState(PackCatalog.vehicles), null)), calls)
        rule.onNodeWithText("Скачать по мобильной сети?").assertExists()
        rule.onNodeWithText("57,3 МБ", substring = true).assertExists()
        rule.onNode(hasText("Скачать") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf("confirm"), calls)
    }

    @Test
    fun aboutListsAllSourcesAndLicenses() {
        rule.setContent { AboutScreen("1.0") }
        rule.onNodeWithText("Версия 1.0").assertExists()
        listOf("Справочник моделей и ТТХ", "Общие коды ошибок (OBDex)", "Заводские коды ошибок", "Расширенные PID (OBDb)", "Встроенная база общих кодов").forEach {
            rule.onRoot().performScrollToNode(hasText(it))
            rule.onNodeWithText(it).assertExists()
        }
        rule.onRoot().performScrollToNode(hasText("Лицензия: CC BY-SA 4.0"))
        rule.onNodeWithText("Лицензия: CC BY-SA 4.0").assertExists()
    }

    @Test
    fun uncoveredGenerationIsExplained() {
        val r = SignalsetResult.NotCovered("OBDb/Test-Model", 2005, Generation("First Generation (AA)", 2002, 2009), emptyList())
        rule.setContent { SignalsetScreen(SignalsetUiState(carTitle = "Тестовая 2005", year = "2005", result = r), onYear = {}, onCheck = {}) }
        rule.onNodeWithText("не покрывает 2005 год", substring = true).assertExists()
        rule.onNodeWithText("First Generation (AA)", substring = true).assertExists()
        rule.onNodeWithText("Опрос расширенных PID пока не поддерживается", substring = true).assertExists()
    }

    @Test
    fun coveredYearListsSignals() {
        val cmd = SignalCommand("7E0", "7E8", "22", "0101", 1.0, null, null, listOf(Signal("TEST_OIL_T", "Oil temperature", "Engine", SignalFormat(len = 8, unit = "celsius"))))
        val r = SignalsetResult.Covered("OBDb/Test-Model", 2012, null, "abc123", listOf(Ecu("7E0", "engine")), listOf(cmd))
        rule.setContent { SignalsetScreen(SignalsetUiState(carTitle = "Тестовая", year = "2012", result = r), onYear = {}, onCheck = {}) }
        rule.onNodeWithText("команд 1, сигналов 1", substring = true).assertExists()
        rule.onNodeWithText("22 0101 → 7E0 · engine").assertExists()
        rule.onNodeWithText("Oil temperature").assertExists()
    }
}

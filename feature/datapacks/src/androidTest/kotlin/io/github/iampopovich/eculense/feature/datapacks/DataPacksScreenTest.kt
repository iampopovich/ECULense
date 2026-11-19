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
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iampopovich.eculense.core.datapacks.PackCatalog
import io.github.iampopovich.eculense.core.datapacks.PackState
import io.github.iampopovich.eculense.core.datapacks.PackStore
import io.github.iampopovich.eculense.core.obdb.Ecu
import io.github.iampopovich.eculense.core.obdb.Generation
import io.github.iampopovich.eculense.core.obdb.Signal
import io.github.iampopovich.eculense.core.obdb.SignalCommand
import io.github.iampopovich.eculense.core.obdb.SignalFormat
import io.github.iampopovich.eculense.core.obdb.SignalsetResult
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import io.github.iampopovich.eculense.core.ui.UiText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataPacksScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int, vararg args: Any): String = ctx.getString(id, *args)

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
        rule.onNodeWithText(str(PackCatalog.vehicles.titleRes)).assertExists()
        rule.onNodeWithText(str(R.string.datapacks_status_downloading_pct, 42)).assertExists()
        rule.onNodeWithText(str(R.string.datapacks_license, str(PackCatalog.vehicles.licenseRes))).assertExists()
        rule.onNodeWithText(str(R.string.datapacks_cancel)).performClick()
        rule.onRoot().performScrollToNode(hasText("2026-11-07T13:44:03.324Z", substring = true))
        rule.onNodeWithText("2026-12-01T00:00:00Z", substring = true).assertExists()
        rule.onNodeWithText(str(R.string.datapacks_update)).performClick()
        rule.onNodeWithText(str(CoreUiR.string.common_delete)).performClick()
        rule.onRoot().performScrollToNode(hasText(str(R.string.datapacks_error, "HTTP 404")))
        rule.onRoot().performScrollToNode(hasText(str(R.string.datapacks_download)))
        rule.onNodeWithText(str(R.string.datapacks_download)).performClick()
        assertEquals(listOf("cancel vehicles", "update obdex", "delete obdex", "download oem-dtc"), calls)
    }

    @Test
    fun meteredDownloadAsksWithSize() {
        val calls = mutableListOf<String>()
        show(DataPacksUiState(packs = packs, confirm = PackConfirm.Metered(PackState(PackCatalog.vehicles), null)), calls)
        rule.onNodeWithText(str(R.string.datapacks_metered_title)).assertExists()
        rule.onNodeWithText(str(R.string.datapacks_size_mb, 60_080_128 / 1024.0 / 1024.0), substring = true).assertExists()
        rule.onNode(hasText(str(R.string.datapacks_download)) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf("confirm"), calls)
    }

    @Test
    fun aboutListsAllSourcesAndLicenses() {
        rule.setContent { AboutScreen("1.0") }
        rule.onNodeWithText(str(R.string.datapacks_version, "1.0")).assertExists()
        listOf(str(PackCatalog.vehicles.titleRes), str(PackCatalog.obdex.titleRes), str(PackCatalog.oemDtc.titleRes), str(R.string.datapacks_obdb_title), str(R.string.datapacks_builtin_title)).forEach {
            rule.onRoot().performScrollToNode(hasText(it))
            rule.onNodeWithText(it).assertExists()
        }
        val ccBySa = str(R.string.datapacks_license, "CC BY-SA 4.0")
        rule.onRoot().performScrollToNode(hasText(ccBySa))
        rule.onNodeWithText(ccBySa).assertExists()
    }

    @Test
    fun uncoveredGenerationIsExplained() {
        val r = SignalsetResult.NotCovered("OBDb/Test-Model", 2005, Generation("First Generation (AA)", 2002, 2009), emptyList())
        rule.setContent { SignalsetScreen(SignalsetUiState(carTitle = UiText.Raw("Test 2005"), year = "2005", result = r), onYear = {}, onCheck = {}) }
        rule.onNodeWithText(str(R.string.datapacks_not_covered_gen, "OBDb/Test-Model", 2005, "First Generation (AA)", "2002–2009")).assertExists()
        rule.onNodeWithText(str(R.string.datapacks_signalset_note)).assertExists()
    }

    @Test
    fun coveredYearListsSignals() {
        val cmd = SignalCommand("7E0", "7E8", "22", "0101", 1.0, null, null, listOf(Signal("TEST_OIL_T", "Oil temperature", "Engine", SignalFormat(len = 8, unit = "celsius"))))
        val r = SignalsetResult.Covered("OBDb/Test-Model", 2012, null, "abc123", listOf(Ecu("7E0", "engine")), listOf(cmd))
        rule.setContent { SignalsetScreen(SignalsetUiState(carTitle = UiText.Raw("Test"), year = "2012", result = r), onYear = {}, onCheck = {}) }
        rule.onNodeWithText(str(R.string.datapacks_covered, "OBDb/Test-Model", 2012, 1, 1, 1, "abc123")).assertExists()
        rule.onNodeWithText("22 0101 → 7E0 · engine").assertExists()
        rule.onNodeWithText("Oil temperature").assertExists()
    }
}

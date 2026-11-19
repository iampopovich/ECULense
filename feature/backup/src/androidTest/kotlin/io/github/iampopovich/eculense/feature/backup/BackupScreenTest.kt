package io.github.iampopovich.eculense.feature.backup

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iampopovich.eculense.core.backup.BackupManifest
import io.github.iampopovich.eculense.core.backup.ImportWorkState
import io.github.iampopovich.eculense.core.data.ImportHistoryItem
import io.github.iampopovich.eculense.core.data.ImportStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupScreenTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int, vararg args: Any): String = ctx.getString(id, *args)
    @get:Rule val rule = createComposeRule()

    private val done = ImportHistoryItem(id = 1, fileName = "Тестовая.cbz", startedAtMs = 1_767_000_000_000, finishedAtMs = 1_767_000_010_000,
        status = ImportStatus.DONE, recordingsAdded = 1, recordingsSkipped = 2, tripsAdded = 1, tripsUpdated = 1, dtcAdded = 1)

    @Test
    fun runningImportShowsProgressAndCancelAndLocksStarting() {
        var cancelled = 0
        rule.setContent { BackupScreen(BackupUiState(folderUri = "content://tree", importState = ImportWorkState.Running(23, 73)), onCancelImport = { cancelled++ }) }
        rule.onNodeWithText(str(R.string.backup_progress, 23, 73)).assertExists()
        rule.onNodeWithText(str(R.string.backup_pick_file)).assertIsNotEnabled()
        rule.onNodeWithText(str(R.string.backup_cancel_import)).performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun finishedImportShowsTheReportAndHistory() {
        rule.setContent { BackupScreen(BackupUiState(importState = ImportWorkState.Finished(ok = true), history = listOf(done))) }
        rule.onNodeWithText(str(R.string.backup_report_done, str(R.string.backup_line_recordings_skipped, 1, 2)), substring = true).assertExists()
        rule.onNodeWithText("Тестовая.cbz", substring = true).assertExists()
        rule.onNodeWithText(str(R.string.backup_pick_file)).assertIsEnabled()
    }

    @Test
    fun newestBackupNeedsAFolderWithCbz() {
        rule.setContent { BackupScreen(BackupUiState(folderUri = "content://tree", folderName = "Backups")) }
        rule.onNodeWithText(str(R.string.backup_folder_no_cbz), substring = true).assertExists()
        rule.onNodeWithText(str(R.string.backup_import_newest)).assertIsNotEnabled()
    }

    @Test
    fun restoreAsksForConfirmation() {
        var confirmed = 0
        val manifest = BackupManifest(1, "io.github.iampopovich.eculense", "0.1.0", 6, 1_767_000_000_000, listOf("ecu.db"), emptyList(), 0)
        rule.setContent {
            BackupScreen(BackupUiState(copy = CopyState(pendingRestore = PendingRestore("content://x", manifest))), onConfirmRestore = { confirmed++ })
        }
        rule.onNodeWithText(str(R.string.backup_restore_text, io.github.iampopovich.eculense.core.ui.EcuDates.dateTime(1_767_000_000_000), "0.1.0")).assertExists()
        rule.onNodeWithText(str(R.string.backup_replace_data)).performClick()
        assertEquals(1, confirmed)
    }
}

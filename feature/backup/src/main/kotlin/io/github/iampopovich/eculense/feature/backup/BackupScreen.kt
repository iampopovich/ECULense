@file:OptIn(ExperimentalLayoutApi::class)

package io.github.iampopovich.eculense.feature.backup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.backup.CompactState
import io.github.iampopovich.eculense.core.backup.StorageSizes
import io.github.iampopovich.eculense.core.backup.EcuBackup
import io.github.iampopovich.eculense.core.backup.ImportWorkState
import io.github.iampopovich.eculense.core.data.ImportHistoryItem
import io.github.iampopovich.eculense.core.data.ImportStatus
import io.github.iampopovich.eculense.core.ui.EcuDates
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import io.github.iampopovich.eculense.core.ui.UiText
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun BackupRoute(viewModel: BackupViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun persist(uri: Uri) = runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        // file access must outlive the screen: import runs in WorkManager; the worker releases it when done
        if (uri != null) { persist(uri); viewModel.importFile(uri, displayName(context, uri)) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) { persist(uri); viewModel.setFolder(uri) }
    }
    val createCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri != null) viewModel.export(uri)
    }
    val pickCopy = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.pickRestore(uri)
    }
    BackupScreen(
        state,
        onPickFile = { pickFile.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
        onPickFolder = { pickFolder.launch(state.folderUri?.let(Uri::parse)) },
        onImportNewest = viewModel::importNewest,
        onCancelImport = viewModel::cancelImport,
        onIncludeAiChat = viewModel::setIncludeAiChat,
        onExport = { createCopy.launch("ECULense-${java.time.LocalDate.now()}.${EcuBackup.EXTENSION}") },
        onRestore = { pickCopy.launch(arrayOf("*/*")) },
        onConfirmRestore = viewModel::confirmRestore,
        onDismissRestore = viewModel::dismissRestore,
        onCompact = viewModel::compact,
    )
}

@Composable
internal fun BackupScreen(
    state: BackupUiState,
    onPickFile: () -> Unit = {},
    onPickFolder: () -> Unit = {},
    onImportNewest: () -> Unit = {},
    onCancelImport: () -> Unit = {},
    onIncludeAiChat: (Boolean) -> Unit = {},
    onExport: () -> Unit = {},
    onRestore: () -> Unit = {},
    onConfirmRestore: () -> Unit = {},
    onDismissRestore: () -> Unit = {},
    onCompact: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.backup_hub_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp)) }
        item { CarScannerCard(state, onPickFile, onPickFolder, onImportNewest, onCancelImport) }
        item { CopyCard(state, onIncludeAiChat, onExport, onRestore) }
        state.storage?.let { sizes -> item(key = "storage") { StorageCard(sizes, state.compact, state.recording, onCompact) } }
        item { Text(stringResource(R.string.backup_history_title), style = MaterialTheme.typography.titleMedium) }
        if (state.history.isEmpty()) {
            item { Secondary(stringResource(R.string.backup_history_empty)) }
        } else {
            items(state.history, key = { it.id }) { HistoryRow(it) }
        }
        item { Secondary(stringResource(R.string.backup_no_gps), Modifier.padding(bottom = 16.dp)) }
    }
    state.copy.pendingRestore?.let { pending ->
        AlertDialog(
            onDismissRequest = onDismissRestore,
            title = { Text(stringResource(R.string.backup_restore_title)) },
            text = {
                Text(stringResource(R.string.backup_restore_text, EcuDates.dateTime(pending.manifest.createdAtMs), pending.manifest.appVersion))
            },
            confirmButton = { Button(onClick = onConfirmRestore, enabled = !state.importing) { Text(stringResource(R.string.backup_replace_data)) } },
            dismissButton = { TextButton(onClick = onDismissRestore) { Text(stringResource(CoreUiR.string.common_cancel)) } },
        )
    }
}

/** R3.2: what the app keeps on the phone and «Compact database» (VACUUM in WorkManager, never while recording). */
@Composable
private fun StorageCard(sizes: StorageSizes, compact: CompactState, recording: Boolean, onCompact: () -> Unit) {
    val context = LocalContext.current
    fun size(bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.backup_storage_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.backup_storage_db, size(sizes.databaseBytes)))
            Text(stringResource(R.string.backup_storage_accel, size(sizes.accelBytes)))
            Text(stringResource(R.string.backup_storage_cache, size(sizes.cacheBytes)))
            Secondary(stringResource(R.string.backup_storage_index_note))
            Secondary(stringResource(R.string.backup_compact_hint))
            val running = compact is CompactState.Running
            FilledTonalButton(onClick = onCompact, enabled = !running && !recording) { Text(stringResource(R.string.backup_compact)) }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            val message = when {
                running -> stringResource(R.string.backup_compact_running)
                recording || compact is CompactState.Recording -> stringResource(R.string.backup_compact_recording)
                compact is CompactState.Done -> stringResource(R.string.backup_compact_done, size(compact.beforeBytes), size(compact.afterBytes))
                compact is CompactState.Failed -> stringResource(R.string.backup_compact_failed)
                else -> null
            }
            message?.let { Text(it) }
        }
    }
}

@Composable
private fun CarScannerCard(state: BackupUiState, onPickFile: () -> Unit, onPickFolder: () -> Unit, onImportNewest: () -> Unit, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Car Scanner", style = MaterialTheme.typography.titleMedium)
            Secondary(stringResource(R.string.backup_cs_note))
            when (val s = state.importState) {
                is ImportWorkState.Running -> {
                    Text(if (s.total > 0) stringResource(R.string.backup_progress, s.done, s.total) else stringResource(R.string.backup_progress_prepare))
                    if (s.total > 0) LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Secondary(stringResource(R.string.backup_background_note))
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.backup_cancel_import)) }
                }
                is ImportWorkState.Finished -> (state.history.firstOrNull()?.let { reportText(it) } ?: s.error?.let { BackupTexts.message(it) }
                    ?: UiText.Res(R.string.backup_report_cancelled).takeIf { s.cancelled })?.let { Text(it.asString()) }
                ImportWorkState.Idle -> Unit
            }
            FilledTonalButton(onClick = onPickFile, enabled = !state.importing) { Text(stringResource(R.string.backup_pick_file)) }
            HorizontalDivider()
            Text(stringResource(R.string.backup_folder_title), style = MaterialTheme.typography.titleSmall)
            Secondary(
                if (state.folderUri != null) {
                    stringResource(
                        CoreUiR.string.common_two_lines,
                        stringResource(R.string.backup_folder, state.folderName ?: stringResource(R.string.backup_folder_selected)),
                        state.newest?.let { n -> stringResource(R.string.backup_folder_newest, n.name, EcuDates.dateTime(n.lastModifiedMs)) }
                            ?: stringResource(R.string.backup_folder_no_cbz),
                    )
                } else {
                    stringResource(R.string.backup_folder_hint)
                },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFolder, enabled = !state.importing) { Text(stringResource(if (state.folderUri == null) R.string.backup_pick_folder else R.string.backup_change_folder)) }
                if (state.folderUri != null) {
                    Button(onClick = onImportNewest, enabled = !state.importing && state.newest != null) { Text(stringResource(R.string.backup_import_newest)) }
                }
            }
        }
    }
}

@Composable
private fun CopyCard(state: BackupUiState, onIncludeAiChat: (Boolean) -> Unit, onExport: () -> Unit, onRestore: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.backup_copy_title), style = MaterialTheme.typography.titleMedium)
            Secondary(stringResource(R.string.backup_copy_note))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.includeAiChat, onCheckedChange = onIncludeAiChat)
                Text(stringResource(R.string.backup_include_ai_chat))
            }
            // with large fonts two buttons don't fit on one line, so wrap the second button rather than the text «Восстановит/ь»
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onExport, enabled = !state.copy.busy) { Text(stringResource(R.string.backup_save_copy)) }
                OutlinedButton(onClick = onRestore, enabled = !state.copy.busy && !state.importing) { Text(stringResource(R.string.backup_restore)) }
            }
            if (state.copy.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.copy.message?.let { Text(it.asString()) }
        }
    }
}

@Composable
private fun HistoryRow(item: ImportHistoryItem) {
    ListItem(
        headlineContent = { Text("${EcuDates.dateTime(item.startedAtMs)} · ${item.fileName}") },
        supportingContent = { Text(historyLine(item).asString()) },
    )
}

@Composable
private fun Secondary(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** Result of the last import: exactly what was added and updated (visibility of system status). */
internal fun reportText(item: ImportHistoryItem): UiText = when (item.status) {
    ImportStatus.DONE -> UiText.Res(R.string.backup_report_done, listOf(historyLine(item)))
    ImportStatus.CANCELLED -> item.message?.let { BackupTexts.message(it) } ?: UiText.Res(R.string.backup_report_cancelled)
    ImportStatus.FAILED -> UiText.Res(R.string.backup_report_failed, listOf(BackupTexts.message(item.message.orEmpty())))
}

internal fun historyLine(item: ImportHistoryItem): UiText = when (item.status) {
    ImportStatus.FAILED -> UiText.Res(R.string.backup_line_failed, listOf(BackupTexts.message(item.message.orEmpty())))
    ImportStatus.CANCELLED -> item.message?.let { BackupTexts.message(it) } ?: UiText.Res(R.string.backup_line_cancelled)
    ImportStatus.DONE -> UiText.Joined(historyParts(item))
}

/** What a finished import changed, one part per kind of data. */
internal fun historyParts(item: ImportHistoryItem): List<UiText> = buildList {
    add(
        if (item.recordingsSkipped > 0) UiText.Res(R.string.backup_line_recordings_skipped, listOf(item.recordingsAdded, item.recordingsSkipped))
        else UiText.Res(R.string.backup_line_recordings, listOf(item.recordingsAdded)),
    )
    add(
        if (item.tripsUpdated > 0) UiText.Res(R.string.backup_line_trips_updated, listOf(item.tripsAdded, item.tripsUpdated))
        else UiText.Res(R.string.backup_line_trips, listOf(item.tripsAdded)),
    )
    add(UiText.Res(R.string.backup_line_dtc, listOf(item.dtcAdded)))
    if (item.carFieldsUpdated > 0) add(UiText.Plural(R.plurals.backup_line_car_fields, item.carFieldsUpdated))
    if (item.unitsUpdated) add(UiText.Res(R.string.backup_line_units))
    if (item.errors > 0) add(UiText.Res(R.string.backup_line_errors, listOf(item.errors)))
}

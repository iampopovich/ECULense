package io.github.iampopovich.eculense.feature.backup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.backup.EcuBackup
import io.github.iampopovich.eculense.core.backup.ImportWorkState
import io.github.iampopovich.eculense.core.data.ImportHistoryItem
import io.github.iampopovich.eculense.core.data.ImportStatus
import io.github.iampopovich.eculense.core.ui.EcuDates
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun BackupRoute(viewModel: BackupViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun persist(uri: Uri) = runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        // доступ к файлу должен пережить экран: импорт идёт в WorkManager; воркер отпустит его по окончании
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
        onExport = { createCopy.launch("ECULense-${EcuDates.date(System.currentTimeMillis()).replace('.', '-')}.${EcuBackup.EXTENSION}") },
        onRestore = { pickCopy.launch(arrayOf("*/*")) },
        onConfirmRestore = viewModel::confirmRestore,
        onDismissRestore = viewModel::dismissRestore,
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
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Импорт и резервные копии", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp)) }
        item { CarScannerCard(state, onPickFile, onPickFolder, onImportNewest, onCancelImport) }
        item { CopyCard(state, onIncludeAiChat, onExport, onRestore) }
        item { Text("История импортов", style = MaterialTheme.typography.titleMedium) }
        if (state.history.isEmpty()) {
            item { Secondary("Импортов ещё не было.") }
        } else {
            items(state.history, key = { it.id }) { HistoryRow(it) }
        }
        item { Secondary("GPS-трек из бэкапа Car Scanner не переносится.", Modifier.padding(bottom = 16.dp)) }
    }
    state.copy.pendingRestore?.let { pending ->
        AlertDialog(
            onDismissRequest = onDismissRestore,
            title = { Text("Восстановить копию?") },
            text = {
                Text(
                    "Копия от ${EcuDates.dateTime(pending.manifest.createdAtMs)} (версия ${pending.manifest.appVersion}) заменит текущие " +
                        "записи, поездки, машины и настройки на этом телефоне. Ключи API останутся. После восстановления приложение перезапустится.",
                )
            },
            confirmButton = { Button(onClick = onConfirmRestore, enabled = !state.importing) { Text("Заменить данные") } },
            dismissButton = { TextButton(onClick = onDismissRestore) { Text("Отмена") } },
        )
    }
}

@Composable
private fun CarScannerCard(state: BackupUiState, onPickFile: () -> Unit, onPickFolder: () -> Unit, onImportNewest: () -> Unit, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Car Scanner", style = MaterialTheme.typography.titleMedium)
            Secondary("Повторный импорт добавляет только новое: записи, поездки, события ошибок. Ваши правки профиля машины не перезаписываются.")
            when (val s = state.importState) {
                is ImportWorkState.Running -> {
                    Text(if (s.total > 0) "Импорт… записи ${s.done} из ${s.total}" else "Импорт… подготовка бэкапа")
                    if (s.total > 0) LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Secondary("Можно свернуть приложение — импорт продолжится, прогресс в уведомлении.")
                    OutlinedButton(onClick = onCancel) { Text("Отменить импорт") }
                }
                is ImportWorkState.Finished -> state.history.firstOrNull()?.let { Text(reportText(it)) } ?: s.error?.let { Text(it) }
                ImportWorkState.Idle -> Unit
            }
            FilledTonalButton(onClick = onPickFile, enabled = !state.importing) { Text("Выбрать файл .cbz") }
            HorizontalDivider()
            Text("Папка бэкапов Car Scanner", style = MaterialTheme.typography.titleSmall)
            Secondary(
                state.folderUri?.let { "Папка: ${state.folderName ?: "выбрана"}" + (state.newest?.let { n -> "\nНовый бэкап: ${n.name} (${EcuDates.dateTime(n.lastModifiedMs)})" } ?: "\nВ папке нет файлов .cbz") }
                    ?: "Выберите папку, куда Car Scanner сохраняет бэкапы, — тогда новый бэкап импортируется одной кнопкой.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onPickFolder, enabled = !state.importing) { Text(if (state.folderUri == null) "Выбрать папку" else "Сменить папку") }
                if (state.folderUri != null) {
                    Button(onClick = onImportNewest, enabled = !state.importing && state.newest != null) { Text("Импортировать новый бэкап") }
                }
            }
        }
    }
}

@Composable
private fun CopyCard(state: BackupUiState, onIncludeAiChat: (Boolean) -> Unit, onExport: () -> Unit, onRestore: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Резервная копия ECULense", style = MaterialTheme.typography.titleMedium)
            Secondary("Один файл .eculense: записи, поездки, машины, ТО и настройки. Ключи API, токены, скачанные справочники и модели в копию не входят.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.includeAiChat, onCheckedChange = onIncludeAiChat)
                Text("Включить историю чата ИИ")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onExport, enabled = !state.copy.busy) { Text("Сохранить копию") }
                OutlinedButton(onClick = onRestore, enabled = !state.copy.busy && !state.importing) { Text("Восстановить") }
            }
            if (state.copy.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.copy.message?.let { Text(it) }
        }
    }
}

@Composable
private fun HistoryRow(item: ImportHistoryItem) {
    ListItem(
        headlineContent = { Text("${EcuDates.dateTime(item.startedAtMs)} · ${item.fileName}") },
        supportingContent = { Text(historyLine(item)) },
    )
}

@Composable
private fun Secondary(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** Итог последнего импорта — что именно добавлено и обновлено (видимость состояния системы). */
internal fun reportText(item: ImportHistoryItem): String = when (item.status) {
    ImportStatus.DONE -> "Готово: " + historyLine(item)
    ImportStatus.CANCELLED -> item.message ?: "Импорт отменён"
    ImportStatus.FAILED -> "Импорт не удался: ${item.message.orEmpty()}"
}

internal fun historyLine(item: ImportHistoryItem): String = when (item.status) {
    ImportStatus.FAILED -> "Ошибка: ${item.message.orEmpty()}"
    ImportStatus.CANCELLED -> item.message ?: "Отменён"
    ImportStatus.DONE -> buildList {
        add("записи +${item.recordingsAdded}" + if (item.recordingsSkipped > 0) " (уже были ${item.recordingsSkipped})" else "")
        add("поездки +${item.tripsAdded}" + if (item.tripsUpdated > 0) ", обновлено ${item.tripsUpdated}" else "")
        add("события ошибок +${item.dtcAdded}")
        if (item.carFieldsUpdated > 0) add("профиль машины: обновлено полей ${item.carFieldsUpdated}")
        if (item.unitsUpdated) add("единицы из бэкапа")
        if (item.errors > 0) add("ошибок ${item.errors}")
    }.joinToString(" · ")
}

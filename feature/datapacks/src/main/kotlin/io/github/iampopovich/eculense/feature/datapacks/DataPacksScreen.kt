package io.github.iampopovich.eculense.feature.datapacks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.datapacks.PackState
import io.github.iampopovich.eculense.core.navigation.AboutDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.navigation.SignalsetDestination
import org.koin.compose.viewmodel.koinViewModel
import java.util.Locale

@Composable
internal fun DataPacksRoute(viewModel: DataPacksViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    DataPacksScreen(
        state = state,
        onDownload = { viewModel.download(it) },
        onUpdate = viewModel::update,
        onDelete = viewModel::askDelete,
        onCancel = viewModel::cancel,
        onConfirm = viewModel::confirm,
        onDismiss = viewModel::dismiss,
        onCheckUpdates = viewModel::checkUpdates,
        onSignalsets = { navigator.navigate(SignalsetDestination) },
        onAbout = { navigator.navigate(AboutDestination) },
    )
}

/** «Данные и справочники»: what is downloaded, version, size, license, source; download/update/delete. */
@Composable
internal fun DataPacksScreen(
    state: DataPacksUiState,
    onDownload: (PackState) -> Unit,
    onUpdate: (PackState) -> Unit,
    onDelete: (PackState) -> Unit,
    onCancel: (PackState) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onCheckUpdates: () -> Unit,
    onSignalsets: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Справочники не входят в приложение: скачиваются по запросу из официальных источников без изменений. " +
                        "Без них всё работает — со встроенной базой общих кодов.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCheckUpdates, enabled = !state.checking) { Text(if (state.checking) "Проверка…" else "Проверить обновления") }
                }
                state.checkMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        items(state.packs, key = { it.manifest.id }) { pack ->
            PackCard(pack, onDownload, onUpdate, onDelete, onCancel)
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Расширенные PID (OBDb)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Сигналы заводских ЭБУ для марки, модели и года активной машины. Пока только просмотр: опрос расширенных PID появится позже.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Лицензия: CC BY-SA 4.0", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onSignalsets) { Text("Открыть для моей машины") }
                }
            }
        }
        item {
            TextButton(onClick = onAbout, modifier = Modifier.padding(bottom = 16.dp)) { Text("О приложении и источниках данных") }
        }
    }
    state.confirm?.let { c ->
        val title = c.pack.manifest.title
        when (c) {
            is PackConfirm.Metered -> AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Скачать по мобильной сети?") },
                text = { Text("«$title» — ${c.pack.manifest.sizeBytes?.let(::formatSize) ?: "размер неизвестен"}. Wi-Fi не подключён, трафик может быть платным.") },
                confirmButton = { TextButton(onClick = onConfirm) { Text("Скачать") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
            )
            is PackConfirm.Delete -> AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Удалить «$title»?") },
                text = { Text("Файлы удалятся с телефона (${c.pack.installed?.sizeBytes?.let(::formatSize).orEmpty()}). Скачать заново можно в любой момент.") },
                confirmButton = { TextButton(onClick = onConfirm) { Text("Удалить") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
            )
        }
    }
}

@Composable
private fun PackCard(
    pack: PackState,
    onDownload: (PackState) -> Unit,
    onUpdate: (PackState) -> Unit,
    onDelete: (PackState) -> Unit,
    onCancel: (PackState) -> Unit,
) {
    val m = pack.manifest
    val uri = LocalUriHandler.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(m.title, style = MaterialTheme.typography.titleMedium)
            Text(m.description, style = MaterialTheme.typography.bodyMedium)
            Text(statusText(pack), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (pack.downloading) {
                val p = pack.progress ?: -1
                if (p >= 0) LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            pack.error?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text("Лицензия: ${m.license}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { runCatching { uri.openUri(m.sourceUrl) } }) { Text("Источник: " + m.sourceUrl.removePrefix("https://")) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    pack.downloading -> OutlinedButton(onClick = { onCancel(pack) }) { Text("Отменить") }
                    pack.installed == null -> Button(onClick = { onDownload(pack) }) { Text("Скачать") }
                    else -> {
                        if (pack.updateVersion != null) Button(onClick = { onUpdate(pack) }) { Text("Обновить") }
                        OutlinedButton(onClick = { onDelete(pack) }) { Text("Удалить") }
                    }
                }
            }
        }
    }
}

/** «Не скачан · 57,3 МБ», «Скачивается 42 %», «Версия v1.0.3 · 57,3 МБ», «… · доступно обновление v1.0.4». */
internal fun statusText(pack: PackState): String {
    val p = pack.progress
    val installed = pack.installed
    return when {
        pack.downloading -> if (p != null && p >= 0) "Скачивается $p %" else "Скачивается…"
        installed == null -> "Не скачан" + (pack.manifest.sizeBytes?.let { " · ${formatSize(it)}" } ?: "")
        else -> "Версия ${installed.version} · ${formatSize(installed.sizeBytes)}" +
            (pack.updateVersion?.let { " · доступно обновление $it" } ?: "")
    }
}

internal fun formatSize(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1) String.format(Locale.getDefault(), "%.1f МБ", mb) else String.format(Locale.getDefault(), "%d КБ", (bytes / 1024).coerceAtLeast(1))
}

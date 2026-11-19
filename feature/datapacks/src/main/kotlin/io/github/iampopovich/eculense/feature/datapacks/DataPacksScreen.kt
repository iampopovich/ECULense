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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.datapacks.PackState
import io.github.iampopovich.eculense.core.navigation.AboutDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.navigation.SignalsetDestination
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import io.github.iampopovich.eculense.core.ui.UiText
import org.koin.compose.viewmodel.koinViewModel

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
                    stringResource(R.string.datapacks_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCheckUpdates, enabled = !state.checking) { Text(stringResource(if (state.checking) R.string.datapacks_checking else R.string.datapacks_check_updates)) }
                }
                state.checkMessage?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall) }
            }
        }
        items(state.packs, key = { it.manifest.id }) { pack ->
            PackCard(pack, onDownload, onUpdate, onDelete, onCancel)
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.datapacks_obdb_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.datapacks_obdb_text),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(stringResource(R.string.datapacks_license, "CC BY-SA 4.0"), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onSignalsets) { Text(stringResource(R.string.datapacks_open_for_my_car)) }
                }
            }
        }
        item {
            TextButton(onClick = onAbout, modifier = Modifier.padding(bottom = 16.dp)) { Text(stringResource(R.string.datapacks_about_link)) }
        }
    }
    state.confirm?.let { c ->
        val title = PackTexts.title(c.pack.manifest).asString()
        when (c) {
            is PackConfirm.Metered -> AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.datapacks_metered_title)) },
                text = {
                    val size = c.pack.manifest.sizeBytes?.let { formatSize(it).asString() } ?: stringResource(R.string.datapacks_size_unknown)
                    Text(stringResource(R.string.datapacks_metered_text, title, size))
                },
                confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.datapacks_download)) } },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiR.string.common_cancel)) } },
            )
            is PackConfirm.Delete -> AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.datapacks_delete_title, title)) },
                text = { Text(stringResource(R.string.datapacks_delete_text, c.pack.installed?.sizeBytes?.let { formatSize(it).asString() }.orEmpty())) },
                confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(CoreUiR.string.common_delete)) } },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiR.string.common_cancel)) } },
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
            Text(PackTexts.title(m).asString(), style = MaterialTheme.typography.titleMedium)
            Text(PackTexts.description(m).asString(), style = MaterialTheme.typography.bodyMedium)
            Text(statusText(pack).asString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (pack.downloading) {
                val p = pack.progress ?: -1
                if (p >= 0) LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            pack.error?.let { Text(stringResource(R.string.datapacks_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.datapacks_license, PackTexts.license(m).asString()), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { runCatching { uri.openUri(m.sourceUrl) } }) { Text(stringResource(R.string.datapacks_source, m.sourceUrl.removePrefix("https://"))) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    pack.downloading -> OutlinedButton(onClick = { onCancel(pack) }) { Text(stringResource(R.string.datapacks_cancel)) }
                    pack.installed == null -> Button(onClick = { onDownload(pack) }) { Text(stringResource(R.string.datapacks_download)) }
                    else -> {
                        if (pack.updateVersion != null) Button(onClick = { onUpdate(pack) }) { Text(stringResource(R.string.datapacks_update)) }
                        OutlinedButton(onClick = { onDelete(pack) }) { Text(stringResource(CoreUiR.string.common_delete)) }
                    }
                }
            }
        }
    }
}

/** «Не скачан · 57,3 МБ», «Скачивается 42 %», «Версия v1.0.3 · 57,3 МБ», «… · доступно обновление v1.0.4». */
internal fun statusText(pack: PackState): UiText {
    val p = pack.progress
    val installed = pack.installed
    return when {
        pack.downloading ->
            if (p != null && p >= 0) UiText.Res(R.string.datapacks_status_downloading_pct, listOf(p)) else UiText.Res(R.string.datapacks_status_downloading)
        installed == null -> pack.manifest.sizeBytes?.let { UiText.Res(R.string.datapacks_status_not_downloaded_size, listOf(formatSize(it))) }
            ?: UiText.Res(R.string.datapacks_status_not_downloaded)
        else -> pack.updateVersion?.let { UiText.Res(R.string.datapacks_status_installed_update, listOf(installed.version, formatSize(installed.sizeBytes), it)) }
            ?: UiText.Res(R.string.datapacks_status_installed, listOf(installed.version, formatSize(installed.sizeBytes)))
    }
}

internal fun formatSize(bytes: Long): UiText {
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1) UiText.Res(R.string.datapacks_size_mb, listOf(mb)) else UiText.Res(R.string.datapacks_size_kb, listOf((bytes / 1024).coerceAtLeast(1)))
}

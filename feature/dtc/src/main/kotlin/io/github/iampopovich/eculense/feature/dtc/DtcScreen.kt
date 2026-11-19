package io.github.iampopovich.eculense.feature.dtc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.dtcdb.DtcInfo
import io.github.iampopovich.eculense.core.dtcdb.DtcSource
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.navigation.AiChatDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.ui.EcuDates
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun DtcRoute(viewModel: DtcViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val resources = LocalResources.current
    DtcScreen(
        state = state,
        onRead = viewModel::read,
        onClear = viewModel::clear,
        onAskAi = { code, info ->
            val prompt = askAiPrompt(code, info) { id, args -> resources.getString(id, *args.toTypedArray()) }
            navigator.navigate(AiChatDestination(prompt))
        },
    )
}

@Composable
internal fun DtcScreen(
    state: DtcUiState,
    onRead: () -> Unit,
    onClear: () -> Unit,
    onAskAi: (String, DtcInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRead, enabled = !state.loading) { Text(stringResource(R.string.dtc_read)) }
            OutlinedButton(onClick = { confirmClear = true }, enabled = !state.loading && state.items.isNotEmpty()) { Text(stringResource(R.string.dtc_clear)) }
        }
        if (state.loading) LinearProgressIndicator()
        state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
        if (state.checked && state.items.isEmpty() && !state.loading) {
            Text(stringResource(R.string.dtc_no_codes))
        }
        LazyColumn {
            items(state.items, key = { "now-" + it.dtc.code + it.dtc.kind }) { item ->
                ListItem(
                    headlineContent = { Text("${item.dtc.code} · ${stringResource(item.dtc.kind.labelRes)}") },
                    supportingContent = { DtcInfoText(item.info) },
                    trailingContent = askAi(state.canAskAi) { onAskAi(item.dtc.code, item.info) },
                )
            }
            if (state.history.isNotEmpty()) {
                item(key = "history-header") {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(stringResource(R.string.dtc_history_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                items(state.history, key = { "history-" + it.code }) { h ->
                    ListItem(
                        headlineContent = { Text("${h.code} · ${stringResource(h.kind.labelRes)}") },
                        overlineContent = { Text(pluralStringResource(R.plurals.dtc_history_seen, h.count, h.count, EcuDates.date(h.lastMs))) },
                        supportingContent = { DtcInfoText(h.info) },
                        trailingContent = askAi(state.canAskAi) { onAskAi(h.code, h.info) },
                    )
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.dtc_clear_title)) },
            text = { Text(stringResource(R.string.dtc_clear_text)) },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text(stringResource(R.string.dtc_clear)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(CoreUiR.string.common_cancel)) } },
        )
    }
}

private fun askAi(enabled: Boolean, onClick: () -> Unit): (@Composable () -> Unit)? =
    if (enabled) {
        { TextButton(onClick = onClick) { Text(stringResource(R.string.dtc_ask_ai)) } }
    } else {
        null
    }

/** Name, plus for OBDex two common causes and symptoms; the source is labeled (whose decoding it is). */
@Composable
internal fun DtcInfoText(info: DtcInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (info.source == DtcSource.OEM_UNKNOWN || info.title == null) {
            Text(info.text, fontStyle = FontStyle.Italic)
        } else {
            Text(info.text)
        }
        if (info.causes.isNotEmpty()) {
            Text(stringResource(R.string.dtc_causes, info.causes.take(2).joinToString("; ")), style = MaterialTheme.typography.bodySmall)
        }
        if (info.symptoms.isNotEmpty()) {
            Text(stringResource(R.string.dtc_symptoms, info.symptoms.take(2).joinToString("; ")), style = MaterialTheme.typography.bodySmall)
        }
        sourceLabel(info)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun sourceLabel(info: DtcInfo): String? = when (info.source) {
    DtcSource.OBDEX -> stringResource(R.string.dtc_source_obdex)
    DtcSource.BUILTIN -> stringResource(R.string.dtc_source_builtin)
    DtcSource.OEM -> stringResource(R.string.dtc_source_oem, info.manufacturer.orEmpty())
    DtcSource.OEM_UNKNOWN, DtcSource.UNKNOWN -> null
}

private val DtcKind.labelRes: Int
    get() = when (this) {
        DtcKind.STORED -> R.string.dtc_kind_stored
        DtcKind.PENDING -> R.string.dtc_kind_pending
        DtcKind.PERMANENT -> R.string.dtc_kind_permanent
    }

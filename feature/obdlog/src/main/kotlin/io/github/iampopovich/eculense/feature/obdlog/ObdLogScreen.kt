package io.github.iampopovich.eculense.feature.obdlog

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.obd.TrafficDirection
import io.github.iampopovich.eculense.core.obd.TrafficEntry
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ObdLogRoute(viewModel: ObdLogViewModel = koinViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chooserTitle = stringResource(R.string.obdlog_chooser)
    ObdLogScreen(
        entries = entries,
        recording = recording,
        onRecordingChange = viewModel::setRecording,
        onShare = {
            scope.launch {
                val uri = viewModel.export() ?: return@launch
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, chooserTitle))
            }
        },
        onClear = viewModel::clear,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ObdLogScreen(
    entries: List<TrafficEntry>,
    recording: Boolean,
    onRecordingChange: (Boolean) -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = recording, role = Role.Switch, onValueChange = onRecordingChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.obdlog_record), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = recording, onCheckedChange = null)
        }
        Text(stringResource(R.string.obdlog_record_hint), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onShare, enabled = entries.isNotEmpty()) { Text(stringResource(R.string.obdlog_share)) }
            OutlinedButton(onClick = onClear, enabled = entries.isNotEmpty()) { Text(stringResource(R.string.obdlog_clear)) }
        }
        Text(stringResource(R.string.obdlog_count, entries.size), style = MaterialTheme.typography.labelLarge)
        HorizontalDivider()
        if (entries.isEmpty()) {
            Text(stringResource(R.string.obdlog_empty))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(entries, key = { index, _ -> entries.size - index }) { _, entry -> EntryRow(entry) }
            }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

@Composable
private fun EntryRow(entry: TrafficEntry) {
    val color = when (entry.direction) {
        TrafficDirection.TX -> MaterialTheme.colorScheme.primary
        TrafficDirection.RX -> MaterialTheme.colorScheme.onSurface
        TrafficDirection.DROP -> MaterialTheme.colorScheme.error
        TrafficDirection.NOTE -> MaterialTheme.colorScheme.tertiary
    }
    val time = timeFormat.format(Instant.ofEpochMilli(entry.timestampMs).atZone(ZoneId.systemDefault()))
    val duration = entry.durationMs?.let { " · " + stringResource(R.string.obdlog_duration, it.toInt()) }.orEmpty()
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            "$time  ${entry.direction.name}$duration",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(entry.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = color)
    }
}

package io.github.iampopovich.eculense.feature.health

import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.analytics.HealthStatus
import io.github.iampopovich.eculense.core.data.HealthCheckRecord
import io.github.iampopovich.eculense.core.data.HealthProgress
import io.github.iampopovich.eculense.core.navigation.AiChatDestination
import io.github.iampopovich.eculense.core.navigation.ConnectDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HealthRoute(checkId: Long? = null, viewModel: HealthViewModel = koinViewModel { parametersOf(checkId) }) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val export: HealthReportExport = koinInject()
    val scope = rememberCoroutineScope()
    // Keeps the print WebView alive until its page is loaded and handed to the print dialog.
    var printView by remember { mutableStateOf<WebView?>(null) }
    HealthScreen(
        state = state,
        onRun = viewModel::run,
        onConnect = { navigator.navigate(ConnectDestination) },
        onOpen = viewModel::open,
        onShare = { r -> scope.launch { export.share(context, r) } },
        onPrint = { r -> scope.launch { export.print(context, r) { printView = it } } },
        onAskAi = { r ->
            val str: Str = { id, args -> resources.getString(id, *args.toTypedArray()) }
            navigator.navigate(AiChatDestination(aiPrompt(str, r, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.takenAtMs)))))
        },
    )
    printView // read so the reference stays in composition
}

/** Prefill only: the user sends it himself. Codes and items in the app language. */
internal fun aiPrompt(str: Str, r: HealthCheckRecord, date: String): String {
    val items = r.result.items.filter { it.status == HealthStatus.ATTENTION || it.status == HealthStatus.FAULT }
        .ifEmpty { r.result.items.filter { it.status == HealthStatus.OK } }
        .joinToString("; ") { it.line(str) }
    val codes = r.dtcs.joinToString(", ") { it.code }.ifEmpty { str.s(R.string.health_no_codes) }
    return str.s(R.string.health_ai_prompt, date, str.s(r.result.verdict.verdictRes()), items, codes)
}

@Composable
internal fun statusColor(s: HealthStatus): Color = when (s) {
    HealthStatus.OK -> Color(0xFF2E7D32)
    HealthStatus.ATTENTION -> Color(0xFFB26A00)
    HealthStatus.FAULT -> MaterialTheme.colorScheme.error
    HealthStatus.NOT_SUPPORTED, HealthStatus.NOT_JUDGED -> MaterialTheme.colorScheme.outline
}

@Composable
internal fun HealthScreen(
    state: HealthUiState,
    onRun: () -> Unit,
    onConnect: () -> Unit,
    onOpen: (HealthCheckRecord) -> Unit,
    onShare: (HealthCheckRecord) -> Unit,
    onPrint: (HealthCheckRecord) -> Unit,
    onAskAi: (HealthCheckRecord) -> Unit,
) {
    val resources = LocalResources.current
    val str: Str = { id, args -> resources.getString(id, *args.toTypedArray()) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "intro") { Text(stringResource(R.string.health_intro), style = MaterialTheme.typography.bodyMedium) }
        item(key = "action") {
            when {
                state.running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    val p = state.progress
                    Text(
                        if (p is HealthProgress.Live) stringResource(R.string.health_live, p.secondsLeft) else stringResource(R.string.health_reading_codes),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                !state.connected -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.health_connect_first))
                    Button(onClick = onConnect, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.health_connect)) }
                }
                else -> Button(onClick = onRun, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.health_run)) }
            }
        }
        (state.progress as? HealthProgress.Failed)?.let { f ->
            item(key = "failed") { Text(stringResource(R.string.health_failed, f.message), color = MaterialTheme.colorScheme.error) }
        }
        state.shown?.let { record ->
            item(key = "verdict") {
                val c = statusColor(record.result.verdict)
                Card(colors = CardDefaults.cardColors(containerColor = c.copy(alpha = 0.12f)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(record.result.verdict.verdictRes()), style = MaterialTheme.typography.titleLarge, color = c)
                        Text(dateFormat.format(Date(record.takenAtMs)), style = MaterialTheme.typography.bodySmall)
                        if (!state.shownStored) Text(stringResource(R.string.health_not_stored), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(record.result.items, key = { "i-${it.id}" }) { item ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = CircleShape, color = statusColor(item.status), modifier = Modifier.padding(top = 6.dp).size(12.dp)) {}
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(item.id.titleRes()), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(stringResource(item.status.labelRes()), item.valueText(str), item.note.res()?.let { stringResource(it) }).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = statusColor(item.status),
                        )
                    }
                }
            }
            if (record.dtcs.isNotEmpty()) {
                item(key = "codes") {
                    Text(stringResource(R.string.health_codes) + ": " + record.dtcs.joinToString(", ") { it.code }, style = MaterialTheme.typography.bodyLarge)
                }
            }
            // Actions on their own rows (large font).
            item(key = "share") { FilledTonalButton(onClick = { onShare(record) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.health_share)) } }
            item(key = "print") { OutlinedButton(onClick = { onPrint(record) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.health_print)) } }
            if (state.aiAvailable) {
                item(key = "ai") { OutlinedButton(onClick = { onAskAi(record) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.health_ask_ai)) } }
            }
        }
        item(key = "history-title") {
            HorizontalDivider()
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.health_history), style = MaterialTheme.typography.titleMedium)
        }
        if (state.history.isEmpty()) {
            item(key = "history-empty") { Text(stringResource(R.string.health_history_empty), style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.history, key = { "h-${it.id}" }) { r ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpen(r) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(shape = CircleShape, color = statusColor(r.result.verdict), modifier = Modifier.size(12.dp)) {}
                Column(Modifier.weight(1f)) {
                    Text(dateFormat.format(Date(r.takenAtMs)), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(r.result.verdict.verdictRes()), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item(key = "end") { Box(Modifier.size(24.dp)) }
    }
}

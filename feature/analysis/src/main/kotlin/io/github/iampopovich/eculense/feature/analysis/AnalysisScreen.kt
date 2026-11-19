package io.github.iampopovich.eculense.feature.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.analyticstext.AnalyticsText
import io.github.iampopovich.eculense.core.data.AnalysisState
import io.github.iampopovich.eculense.core.data.SessionSummary
import io.github.iampopovich.eculense.core.data.StoredFinding
import io.github.iampopovich.eculense.core.navigation.AiChatDestination
import io.github.iampopovich.eculense.core.navigation.FindingDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.navigation.SessionDetailDestination
import io.github.iampopovich.eculense.core.ui.CarNames
import io.github.iampopovich.eculense.core.ui.EcuDates
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.util.Locale

@Composable
internal fun AnalysisRoute(viewModel: AnalysisViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    AnalysisScreen(state, onRecompute = viewModel::recompute, onOpen = { navigator.navigate(FindingDestination(it.id)) })
}

/**
 * List of findings for the active car: first what needs attention (with a severity label, not just color),
 * then "no data" and "normal". Recalculation runs in the background with progress; the screen isn't blocked.
 */
@Composable
internal fun AnalysisScreen(
    state: AnalysisUiState,
    onRecompute: () -> Unit,
    onOpen: (StoredFinding) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.analysis_hub_title), style = MaterialTheme.typography.headlineSmall)
                state.car?.let { Secondary(CarNames.display(it)) }
            }
        }
        item { StatusRow(state, onRecompute) }
        if (state.loading) return@LazyColumn
        if (state.findings.isEmpty() && !state.running) {
            item { Secondary(stringResource(R.string.analysis_empty)) }
            return@LazyColumn
        }
        if (state.problems.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.analysis_section_attention)) }
            items(state.problems, key = { it.id }) { FindingCard(it, onOpen) }
        } else if (state.findings.isNotEmpty()) {
            item { Secondary(stringResource(R.string.analysis_no_problems)) }
        }
        if (state.noData.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.analysis_section_no_data)) }
            items(state.noData, key = { it.id }) { FindingCard(it, onOpen) }
        }
        if (state.ok.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.analysis_section_ok)) }
            items(state.ok, key = { it.id }) { FindingCard(it, onOpen) }
        }
        item { Secondary(stringResource(R.string.analysis_disclaimer), Modifier.padding(bottom = 16.dp)) }
    }
}

@Composable
private fun StatusRow(state: AnalysisUiState, onRecompute: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val a = state.analysis) {
            is AnalysisState.Running -> {
                Secondary(stringResource(R.string.analysis_progress, a.done, a.total))
                LinearProgressIndicator(
                    progress = { if (a.total == 0) 0f else a.done.toFloat() / a.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is AnalysisState.Done -> Secondary(stringResource(R.string.analysis_done, EcuDates.dateTime(a.atMs), a.sessions, stringResource(R.string.analysis_seconds, seconds(a.durationMs))))
            is AnalysisState.Failed -> Text(stringResource(R.string.analysis_failed, a.message), color = MaterialTheme.colorScheme.error)
            AnalysisState.Idle -> state.findings.maxOfOrNull { it.createdAtMs }?.let { Secondary(stringResource(R.string.analysis_updated, EcuDates.dateTime(it))) }
        }
        OutlinedButton(onClick = onRecompute, enabled = !state.running) { Text(stringResource(R.string.analysis_recompute)) }
    }
}

private fun seconds(ms: Long) = String.format(Locale.getDefault(), "%.1f", ms / 1000.0)

@Composable
private fun FindingCard(f: StoredFinding, onOpen: (StoredFinding) -> Unit) {
    val (container, content) = f.finding.severity.colors()
    val context = LocalContext.current
    Card(onClick = { onOpen(f) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeverityBadge(f.finding.severity)
                Text(AnalyticsText.title(context, f.finding), style = MaterialTheme.typography.titleMedium, color = content)
            }
            Text(AnalyticsText.message(context, f.finding), style = MaterialTheme.typography.bodyMedium, color = content, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun SeverityBadge(severity: Severity) {
    val color = when (severity) {
        Severity.CRITICAL -> MaterialTheme.colorScheme.error
        Severity.WARNING -> MaterialTheme.colorScheme.tertiary
        Severity.INFO -> MaterialTheme.colorScheme.secondary
        Severity.NO_DATA, Severity.OK -> MaterialTheme.colorScheme.outline
    }
    val onColor = when (severity) {
        Severity.CRITICAL -> MaterialTheme.colorScheme.onError
        Severity.WARNING -> MaterialTheme.colorScheme.onTertiary
        Severity.INFO -> MaterialTheme.colorScheme.onSecondary
        Severity.NO_DATA, Severity.OK -> MaterialTheme.colorScheme.surface
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(AnalyticsText.severity(LocalContext.current, severity), style = MaterialTheme.typography.labelMedium, color = onColor, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun Severity.colors(): Pair<Color, Color> = when (this) {
    Severity.CRITICAL -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    Severity.WARNING -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    Severity.INFO -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    Severity.NO_DATA, Severity.OK -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
}

@Composable
internal fun FindingRoute(findingId: Long, viewModel: FindingViewModel = koinViewModel { parametersOf(findingId) }) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    FindingScreen(
        state,
        onAskAi = { navigator.navigate(AiChatDestination(askAiPrompt(context, it))) },
        onOpenSession = { navigator.navigate(SessionDetailDestination(it)) },
    )
}

/** Finding details: text, evidence (numbers and recordings), «Ask AI» if the chat module is present. */
@Composable
internal fun FindingScreen(
    state: FindingUiState,
    onAskAi: (StoredFinding) -> Unit,
    onOpenSession: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val f = state.finding
        if (f == null) {
            item { Secondary(stringResource(if (state.loading) R.string.analysis_loading else R.string.analysis_finding_missing), Modifier.padding(top = 16.dp)) }
            return@LazyColumn
        }
        val x = f.finding
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SeverityBadge(x.severity)
                Text(AnalyticsText.title(context, x), style = MaterialTheme.typography.headlineSmall)
                Text(AnalyticsText.message(context, x), style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (x.evidence.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.analysis_evidence)) }
            items(x.evidence) { e ->
                Column {
                    Text(AnalyticsText.label(context, e), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(AnalyticsText.value(context, e), style = MaterialTheme.typography.bodyLarge)
                    e.rawLabel?.let { state.dtc[it] }?.let { note ->
                        Text(note.text.asString(), style = MaterialTheme.typography.bodyMedium)
                        if (note.causes.isNotEmpty()) {
                            Text(stringResource(R.string.analysis_common_causes, note.causes.joinToString("; ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (x.fromMs != null && x.toMs != null) {
            item { Secondary(stringResource(R.string.analysis_period, EcuDates.date(x.fromMs!!), EcuDates.date(x.toMs!!))) }
        }
        if (state.sessions.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.analysis_recordings, state.sessions.size)) }
            items(state.sessions.take(MAX_SESSIONS), key = { it.id }) { s -> SessionLink(s, onOpenSession) }
            if (state.sessions.size > MAX_SESSIONS) item { Secondary(stringResource(R.string.analysis_and_more, state.sessions.size - MAX_SESSIONS)) }
        }
        if (state.canAskAi && x.severity != Severity.OK) {
            item {
                Button(onClick = { onAskAi(f) }, modifier = Modifier.padding(vertical = 8.dp)) { Text(stringResource(R.string.analysis_ask_ai)) }
            }
        }
        item { Secondary(stringResource(R.string.analysis_computed, EcuDates.dateTime(f.createdAtMs)), Modifier.padding(bottom = 16.dp)) }
    }
}

private const val MAX_SESSIONS = 10

@Composable
private fun SessionLink(s: SessionSummary, onOpen: (Long) -> Unit) {
    TextButton(onClick = { onOpen(s.id) }) { Text(EcuDates.dateTime(s.startedAtMs)) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Secondary(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

package io.github.iampopovich.eculense.core.ailocal

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.ui.UiText
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import org.koin.compose.viewmodel.koinViewModel

/** Screen actions; empty by default, handy in UI tests. */
internal class LocalModelActions(
    val onDownload: (String) -> Unit = {},
    val onConfirmDownload: () -> Unit = {},
    val onDismissConfirm: () -> Unit = {},
    val onCancelDownload: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onBench: (String) -> Unit = {},
    val onUse: (String) -> Unit = {},
    val onUseBackend: (String, BackendKind) -> Unit = { _, _ -> },
    val onStopUsing: () -> Unit = {},
    val onTokenInput: (String) -> Unit = {},
    val onSaveToken: () -> Unit = {},
    val onDeleteToken: () -> Unit = {},
    val onLinkInput: (String) -> Unit = {},
    val onAddLink: () -> Unit = {},
    val onPickFile: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
    val onSelect: (String?) -> Unit = {},
    val onRefreshCatalog: () -> Unit = {},
    val onSearchQuery: (String) -> Unit = {},
    val onSearch: () -> Unit = {},
)

@Composable
internal fun LocalModelRoute(viewModel: LocalModelViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            var name = "model.litertlm"
            var size: Long? = null
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: name
                    size = if (c.isNull(1)) null else c.getLong(1)
                }
            }
            viewModel.importFile(uri, name, size)
        }
    }
    LocalModelScreen(
        state,
        LocalModelActions(
            onDownload = viewModel::requestDownload,
            onConfirmDownload = viewModel::confirmDownload,
            onDismissConfirm = viewModel::dismissConfirm,
            onCancelDownload = viewModel::cancelDownload,
            onDelete = viewModel::delete,
            onBench = viewModel::runBench,
            onUse = viewModel::use,
            onUseBackend = viewModel::useBackend,
            onStopUsing = viewModel::stopUsing,
            onTokenInput = viewModel::setTokenInput,
            onSaveToken = viewModel::saveToken,
            onDeleteToken = viewModel::deleteToken,
            onLinkInput = viewModel::setLinkInput,
            onAddLink = viewModel::addLink,
            onPickFile = { picker.launch(arrayOf("*/*")) },
            onMessageShown = viewModel::messageShown,
            onSelect = viewModel::select,
            onRefreshCatalog = { viewModel.refreshCatalog() },
            onSearchQuery = viewModel::setSearchQuery,
            onSearch = viewModel::search,
        ),
    )
}

/**
 * «На устройстве» screen: compact phone card, then ONE list of models by size (row = name,
 * size, source badge, estimate, action/result icon); details and actions in a bottom sheet on tap.
 */
@Composable
internal fun LocalModelScreen(state: LocalModelUiState, actions: LocalModelActions, modifier: Modifier = Modifier) {
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it.asString(context))
            actions.onMessageShown()
        }
    }
    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("local-model-list")) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ailocal_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                    DeviceCard(state.device, state.nano)
                    Text(
                        stringResource(R.string.ailocal_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { CatalogHeader(state, actions) }
            items(state.rows, key = { it.model.packId }) { row -> ModelListRow(row, state.hasToken, actions) }
            item { SectionTitle(stringResource(R.string.ailocal_section_hf)) }
            item { SearchBox(state, actions) }
            state.searchResults?.let { results ->
                if (results.isEmpty() && !state.searchBusy) {
                    item { Text(stringResource(R.string.ailocal_search_empty), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
                }
                items(results, key = { "hf-" + it.model.packId }) { row -> ModelListRow(row, state.hasToken, actions) }
            }
            item { SectionTitle(stringResource(R.string.ailocal_section_custom)) }
            item { Box(Modifier.padding(horizontal = 16.dp)) { CustomModelCard(state, actions) } }
            item { Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { TokenCard(state, actions) } }
            item { Box(Modifier.padding(bottom = 24.dp)) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    val selected = state.selected?.let { id -> state.rows.firstOrNull { it.model.packId == id } ?: state.searchResults?.firstOrNull { it.model.packId == id } }
    if (selected != null) ModelSheet(selected, state.hasToken, actions)
    state.confirm?.let { ConfirmDialog(it, actions) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun CatalogHeader(state: LocalModelUiState, actions: LocalModelActions) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ailocal_catalog_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.ailocal_catalog_source, state.catalogVersion.replace('_', '.')),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.catalogRefreshing) {
            CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = actions.onRefreshCatalog) { Icon(painterResource(R.drawable.ic_model_refresh), contentDescription = stringResource(R.string.ailocal_catalog_refresh)) }
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceProfile?, nano: NanoStatus?) {
    Card(Modifier.fillMaxWidth().testTag("device-card")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.ailocal_device_title), style = MaterialTheme.typography.titleSmall)
            if (device == null) {
                Text(stringResource(R.string.ailocal_checking), style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            Text(
                stringResource(
                    R.string.ailocal_device_memory,
                    FitEstimator.gb(device.totalRamBytes),
                    FitEstimator.gb(device.availRamBytes),
                    FitEstimator.gb(device.freeStorageBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                listOf(
                    (device.soc ?: stringResource(R.string.ailocal_unknown)).let { soc ->
                        if (device.arm64) soc else stringResource(R.string.ailocal_device_no_arm64, soc)
                    },
                    stringResource(if (device.gpu) R.string.ailocal_device_gpu_yes else R.string.ailocal_device_gpu_no),
                    device.npu?.let { stringResource(R.string.ailocal_device_npu, it) } ?: stringResource(R.string.ailocal_device_npu_no),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            nano?.let { Text(it.text.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Green/amber with contrast ≥ 4.5:1 on both light and dark themes (surface detected by luminance). */
private fun ColorScheme.dark(): Boolean = surface.luminance() < 0.5f
private fun ColorScheme.good(): Color = if (dark()) Color(0xFF81C784) else Color(0xFF1B5E20)
private fun ColorScheme.warn(): Color = if (dark()) Color(0xFFFFB74D) else Color(0xFF8D5A00)

private fun fitColor(fit: Fit, scheme: ColorScheme): Color = when (fit) {
    Fit.OK -> scheme.good()
    Fit.TIGHT -> scheme.warn()
    Fit.UNLIKELY -> scheme.error
    Fit.UNKNOWN -> scheme.onSurfaceVariant
}

private fun verdictColor(v: Verdict, scheme: ColorScheme): Color = when (v) {
    Verdict.COMFORT -> scheme.good()
    Verdict.SLOW -> scheme.warn()
    Verdict.UNSUITABLE -> scheme.error
}

/** Small badge: source, «actions only», «HF token needed», estimate. */
@Composable
private fun Badge(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, strong: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ModelBadges(row: ModelRow, hasToken: Boolean, withFit: Boolean = true) {
    val m = row.model
    val scheme = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Badge(stringResource(m.origin.badgeRes))
        if (m.koog && m.origin != ModelOrigin.KOOG) Badge(stringResource(ModelOrigin.KOOG.badgeRes))
        if (m.actionsOnly) Badge(stringResource(R.string.ailocal_badge_actions_only), scheme.tertiary)
        if (m.gated && !hasToken && !row.installed) Badge(stringResource(R.string.ailocal_badge_needs_token), scheme.error)
        if (withFit && !row.installed) Badge(stringResource(row.fit.fit.labelRes).lowercase(), fitColor(row.fit.fit, scheme), strong = true)
    }
}

/** One list row: name, size, badges, and an action/state icon on the right. Tap opens details. */
@Composable
private fun ModelListRow(row: ModelRow, hasToken: Boolean, actions: LocalModelActions) {
    val m = row.model
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable { actions.onSelect(m.packId) }
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .testTag("model-${m.packId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(m.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(m.sizeBytes?.let { FitEstimator.size(it).asString() } ?: "?", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ModelBadges(row, hasToken)
        }
        Box(Modifier.heightIn(min = 56.dp).widthIn(min = 56.dp).padding(end = 8.dp), contentAlignment = Alignment.Center) { RowTrailing(row, actions) }
    }
    HorizontalDivider(Modifier.padding(start = 16.dp))
}

@Composable
private fun RowTrailing(row: ModelRow, actions: LocalModelActions) {
    val m = row.model
    val downloading = stringResource(R.string.ailocal_cd_downloading)
    when {
        row.downloading -> {
            if (row.progress != null && row.progress >= 0) {
                // Lambda is stable and the value is read at draw time: frequent progress updates don't recompose
                // the indicator (otherwise list scroll gestures got stuck on the phone).
                val pct by rememberUpdatedState(row.progress / 100f)
                CircularProgressIndicator(progress = { pct }, modifier = Modifier.size(32.dp).semantics { contentDescription = downloading }, strokeWidth = 3.dp)
            } else {
                CircularProgressIndicator(Modifier.size(32.dp).semantics { contentDescription = downloading }, strokeWidth = 3.dp)
            }
        }
        row.installed && row.benching -> {
            val benching = stringResource(R.string.ailocal_cd_benching)
            CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = benching }, strokeWidth = 2.dp)
        }
        row.installed && row.report != null -> {
            val v = row.report.verdict
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (row.activeBackend != null) Icon(painterResource(R.drawable.ic_model_check), contentDescription = stringResource(R.string.ailocal_cd_in_use), tint = MaterialTheme.colorScheme.good(), modifier = Modifier.size(18.dp))
                Text(stringResource(v.labelRes), color = verdictColor(v, MaterialTheme.colorScheme), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        row.installed -> Icon(painterResource(R.drawable.ic_model_check), contentDescription = stringResource(R.string.ailocal_cd_downloaded), tint = MaterialTheme.colorScheme.primary)
        m.url != null -> IconButton(onClick = { actions.onDownload(m.packId) }) {
            Icon(painterResource(R.drawable.ic_model_download), contentDescription = stringResource(if (row.partial) R.string.ailocal_cd_resume else R.string.ailocal_cd_download, m.title))
        }
    }
}

@Composable
private fun SearchBox(state: LocalModelUiState, actions: LocalModelActions) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.ailocal_search_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = actions.onSearchQuery,
            label = { Text(stringResource(R.string.ailocal_search_label)) },
            singleLine = true,
            isError = state.searchError != null,
            supportingText = state.searchError?.let { { Text(it.asString()) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.onSearch() }),
            trailingIcon = {
                if (state.searchBusy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else IconButton(onClick = actions.onSearch, enabled = state.searchQuery.isNotBlank()) { Icon(painterResource(R.drawable.ic_model_search), contentDescription = stringResource(R.string.ailocal_search_action)) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(row: ModelRow, hasToken: Boolean, actions: LocalModelActions) {
    val m = row.model
    val uri = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = { actions.onSelect(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp).testTag("model-sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(m.title, style = MaterialTheme.typography.titleLarge)
            ModelBadges(row, hasToken, withFit = false)
            Text(
                listOfNotNull(m.sizeBytes?.let { FitEstimator.size(it).asString() } ?: stringResource(R.string.ailocal_size_unknown), m.fileName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            m.descriptionText.asString().takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (m.actionsOnly) Text(stringResource(R.string.ailocal_actions_only_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            val facts = listOfNotNull(
                m.accelerators.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.ailocal_accelerators, it.joinToString(", ") { a -> a.uppercase() }) },
                m.minRamGb?.let { stringResource(R.string.ailocal_gallery_min_ram, it) },
                when {
                    m.license.isNotBlank() -> stringResource(R.string.ailocal_license, m.license)
                    m.sourceUrl.isNotBlank() -> stringResource(R.string.ailocal_license, stringResource(R.string.ailocal_license_see_page))
                    else -> null
                },
            )
            facts.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (m.sourceUrl.isNotBlank()) {
                TextButton(onClick = { runCatching { uri.openUri(m.sourceUrl) } }) { Text(stringResource(R.string.ailocal_model_page)) }
            }
            if (row.report == null) {
                Text(stringResource(R.string.ailocal_estimate, stringResource(row.fit.fit.labelRes)), color = fitColor(row.fit.fit, MaterialTheme.colorScheme), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                row.fit.reasons.forEach { Text(it.asString(), style = MaterialTheme.typography.bodySmall) }
            }
            if (m.gated && !hasToken && !row.installed) {
                Text(stringResource(R.string.ailocal_gated_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider()
            when {
                row.downloading -> {
                    val total = m.sizeBytes
                    if (row.progress != null && row.progress >= 0) {
                        val pct by rememberUpdatedState(row.progress / 100f)
                        LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    val done = FitEstimator.size(row.downloadedBytes).asString()
                    val amount = total?.let { stringResource(R.string.ailocal_size_of, done, FitEstimator.size(it).asString()) } ?: done
                    Text(
                        stringResource(if (row.retrying) R.string.ailocal_download_retrying else R.string.ailocal_download_progress, amount),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Waiting to retry after a drop: tapping restarts the download immediately (from the open app).
                        if (row.retrying) Button(onClick = { actions.onDownload(m.packId) }) { Text(stringResource(R.string.ailocal_retry_now)) }
                        OutlinedButton(onClick = { actions.onCancelDownload(m.packId) }) { Text(stringResource(R.string.ailocal_stop)) }
                    }
                }
                !row.installed -> {
                    row.error?.let { Text(stringResource(R.string.ailocal_download_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (m.url == null) {
                        Text(stringResource(R.string.ailocal_no_link), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { actions.onDownload(m.packId) }) {
                                Text(if (row.partial) stringResource(R.string.ailocal_resume, FitEstimator.size(row.downloadedBytes).asString()) else stringResource(R.string.ailocal_download))
                            }
                            if (row.partial) TextButton(onClick = { actions.onDelete(m.packId) }) { Text(stringResource(CoreUiR.string.common_delete)) }
                        }
                    }
                }
                else -> InstalledPart(row, actions)
            }
        }
    }
}

@Composable
private fun InstalledPart(row: ModelRow, actions: LocalModelActions) {
    val m = row.model
    if (row.benching) {
        Text(stringResource(R.string.ailocal_bench_running, row.benchingBackend?.let { "${it.label}…" } ?: "…"), fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(Modifier.fillMaxWidth())
        row.benchDone.forEach { RunLine(it) }
        Text(stringResource(R.string.ailocal_bench_isolated), style = MaterialTheme.typography.bodySmall)
        return
    }
    row.report?.let { VerdictCard(it, row, actions) } ?: Text(stringResource(R.string.ailocal_not_benched), style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (row.activeBackend == null) {
            Button(onClick = { actions.onUse(m.packId) }) { Text(stringResource(R.string.ailocal_use)) }
        } else {
            OutlinedButton(onClick = actions.onStopUsing) { Text(stringResource(R.string.ailocal_stop_using)) }
        }
        OutlinedButton(onClick = { actions.onBench(m.packId) }) { Text(stringResource(if (row.report == null) R.string.ailocal_bench else R.string.ailocal_bench_again)) }
        TextButton(onClick = { actions.onDelete(m.packId) }) { Text(stringResource(CoreUiR.string.common_delete)) }
    }
}

@Composable
private fun VerdictCard(report: BenchReport, row: ModelRow, actions: LocalModelActions) {
    val verdict = report.verdict
    Card(Modifier.fillMaxWidth().testTag("verdict-${row.model.packId}"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.ailocal_verdict, stringResource(verdict.labelRes)), color = verdictColor(verdict, MaterialTheme.colorScheme), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    when (verdict) {
                        Verdict.COMFORT -> R.string.ailocal_verdict_comfort_desc
                        Verdict.SLOW -> R.string.ailocal_verdict_slow_desc
                        Verdict.UNSUITABLE -> R.string.ailocal_verdict_unsuitable_desc
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            report.best?.let { Text(stringResource(R.string.ailocal_best_backend, it.backend.label), fontWeight = FontWeight.SemiBold) }
            report.runs.forEach { RunLine(it) }
            Text(stringResource(R.string.ailocal_bench_params, report.runs.firstOrNull { it.ok }?.prefillTokens ?: BenchService.PREFILL_TOKENS, BenchService.DECODE_TOKENS), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            row.activeBackend?.let { active ->
                Text(stringResource(R.string.ailocal_in_use, active.label), fontWeight = FontWeight.SemiBold)
                val others = report.runs.filter { it.ok && it.backend != active }
                if (others.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        others.forEach { r -> AssistChip(onClick = { actions.onUseBackend(row.model.packId, r.backend) }, label = { Text(stringResource(R.string.ailocal_switch_to, r.backend.label)) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunLine(run: BackendRun) {
    val text = if (run.ok) {
        listOfNotNull(
            stringResource(R.string.ailocal_run_ok, run.backend.label, sec(run.ttftSeconds), num(run.decodeTokensPerSecond), num(run.prefillTokensPerSecond), sec(run.initSeconds)),
            run.peakRssBytes?.let { stringResource(R.string.ailocal_run_memory, FitEstimator.gb(it)) },
        ).joinToString(" · ")
    } else {
        val reason = when (run.failure) {
            BenchFailure.TIMEOUT -> stringResource(R.string.ailocal_bench_timeout, BenchRunner.TIMEOUT_MINUTES)
            BenchFailure.CRASHED -> stringResource(R.string.ailocal_bench_crashed)
            BenchFailure.NOT_STARTED -> stringResource(R.string.ailocal_bench_not_started)
            null -> run.error ?: stringResource(R.string.ailocal_run_error)
        }
        stringResource(R.string.ailocal_run_failed, run.backend.label, reason)
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (run.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
}

private fun sec(v: Double) = String.format(java.util.Locale.getDefault(), "%.1f", v)
private fun num(v: Double) = String.format(java.util.Locale.getDefault(), "%.1f", v)

@Composable
private fun CustomModelCard(state: LocalModelUiState, actions: LocalModelActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ailocal_custom_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = state.linkInput,
            onValueChange = actions.onLinkInput,
            label = { Text(stringResource(R.string.ailocal_link_label)) },
            placeholder = { Text("https://huggingface.co/…/resolve/main/model.litertlm") },
            singleLine = true,
            isError = state.linkError != null,
            supportingText = state.linkError?.let { { Text(it.asString()) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onAddLink, enabled = state.linkInput.isNotBlank() && !state.linkBusy) { Text(stringResource(if (state.linkBusy) R.string.ailocal_checking else R.string.ailocal_check_link)) }
            OutlinedButton(onClick = actions.onPickFile, enabled = state.importProgress == null) { Text(stringResource(R.string.ailocal_pick_file)) }
        }
        state.importProgress?.let { Text(stringResource(R.string.ailocal_copying, FitEstimator.size(it).asString()), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun TokenCard(state: LocalModelUiState, actions: LocalModelActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ailocal_token_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.ailocal_token_desc),
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.hasToken) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ailocal_token_saved), modifier = Modifier.weight(1f))
                TextButton(onClick = actions.onDeleteToken) { Text(stringResource(R.string.ailocal_token_delete)) }
            }
        } else {
            OutlinedTextField(
                value = state.tokenInput,
                onValueChange = actions.onTokenInput,
                label = { Text("hf_…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = actions.onSaveToken, enabled = state.tokenInput.isNotBlank()) { Text(stringResource(R.string.ailocal_token_save)) }
        }
    }
}

@Composable
private fun ConfirmDialog(c: DownloadConfirm, actions: LocalModelActions) {
    AlertDialog(
        onDismissRequest = actions.onDismissConfirm,
        title = { Text(stringResource(R.string.ailocal_confirm_title, c.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ailocal_confirm_size, c.sizeBytes?.let { FitEstimator.size(it).asString() } ?: stringResource(R.string.ailocal_unknown), stringResource(c.fit.fit.labelRes)))
                c.fit.reasons.forEach { Text(it.asString(), style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.ailocal_confirm_risk))
                if (c.adopt != null) Text(stringResource(R.string.ailocal_confirm_hf_unverified), style = MaterialTheme.typography.bodySmall)
                if (c.metered) Text(stringResource(R.string.ailocal_confirm_metered), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                if (c.gatedWithoutToken) Text(stringResource(R.string.ailocal_confirm_gated), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = actions.onConfirmDownload) { Text(stringResource(if (c.metered) R.string.ailocal_download_metered else R.string.ailocal_download)) } },
        dismissButton = { TextButton(onClick = actions.onDismissConfirm) { Text(stringResource(CoreUiR.string.common_cancel)) } },
    )
}

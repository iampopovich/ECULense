package io.github.iampopovich.eculense.feature.dtc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.obd.ModuleDtcRead
import io.github.iampopovich.eculense.core.obd.ModuleDtcStatus
import io.github.iampopovich.eculense.core.obdtext.ObdText
import io.github.iampopovich.eculense.core.ui.EcuDates
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun VehicleModulesRoute(viewModel: VehicleModulesViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    VehicleModulesScreen(state, onScan = viewModel::scan, onOpen = viewModel::open, onClose = viewModel::close)
}

@Composable
internal fun VehicleModulesScreen(state: ModulesUiState, onScan: () -> Unit, onOpen: (Int) -> Unit, onClose: () -> Unit) {
    val selected = state.selected?.let { state.rows.getOrNull(it) }
    if (selected != null) {
        BackHandler(onBack = onClose)
        ModuleDetail(selected, onClose)
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "head") {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.modules_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.modules_intro), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onScan, enabled = state.canScan) { Text(stringResource(R.string.modules_scan)) }
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                // The reason of a disabled button is always visible: a silent disabled button reads as «the tap did nothing».
                when (state.scanBlock) {
                    ScanBlock.NOT_CONNECTED -> Text(stringResource(R.string.modules_need_connection), color = muted)
                    ScanBlock.MOVING -> Text(stringResource(R.string.modules_stop_car), color = MaterialTheme.colorScheme.error)
                    ScanBlock.WAITING_SPEED -> Text(stringResource(R.string.modules_waiting_speed), color = muted)
                    ScanBlock.SCANNING, null -> Unit
                }
                if (state.scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.progress?.let { Text(it.asString(), color = muted) }
                }
                state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                if (state.limited) Text(stringResource(R.string.modules_limited), color = muted)
                // Fixed note: the policy never addresses airbag modules.
                Text(stringResource(R.string.modules_srs_note), style = MaterialTheme.typography.bodySmall, color = muted)
                state.scannedAtMs?.takeIf { state.fromHistory }?.let {
                    Text(stringResource(R.string.modules_last_scan, EcuDates.date(it)), style = MaterialTheme.typography.labelMedium, color = muted)
                }
                if (state.checked && state.rows.isEmpty() && !state.scanning) Text(stringResource(R.string.modules_none))
            }
        }
        itemsIndexed(state.rows, key = { _, r -> "m-" + r.address }) { i, row -> ModuleRowItem(row) { onOpen(i) } }
        item(key = "bottom") { Spacer(Modifier.padding(8.dp)) }
    }
}

@Composable
private fun ModuleRowItem(row: ModuleRow, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(ObdText.moduleName(row.kind, row.address).asString()) },
        supportingContent = { Text(stringResource(R.string.modules_row_address, row.address), color = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { CountChip(row) },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun CountChip(row: ModuleRow) {
    val count = row.dtcCount
    val (text, bg, fg) = when {
        count == null -> Triple(statusText(row.read), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        count == 0 -> Triple(stringResource(R.string.modules_no_codes), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        else -> Triple(stringResource(R.string.modules_codes_count, count), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    }
    Surface(color = bg, shape = MaterialTheme.shapes.small) {
        Text(text, color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun statusText(read: ModuleDtcRead): String = when (read) {
    is ModuleDtcRead.Codes -> stringResource(R.string.modules_codes_count, read.dtcs.size)
    ModuleDtcRead.NotSupported -> stringResource(R.string.modules_not_supported)
    ModuleDtcRead.NoReply -> stringResource(R.string.modules_no_reply)
    is ModuleDtcRead.Refused -> stringResource(R.string.modules_refused, "%02X".format(read.nrc))
}

@Composable
private fun ModuleDetail(row: ModuleRow, onClose: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "head") {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.modules_back)) }
                Text(ObdText.moduleName(row.kind, row.address).asString(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.modules_row_address, row.address), color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    row.read !is ModuleDtcRead.Codes -> Text(statusText(row.read))
                    row.items.isEmpty() -> Text(stringResource(R.string.modules_no_codes))
                }
            }
        }
        items(row.items, key = { "c-" + it.dtc.code }) { item ->
            ListItem(
                headlineContent = { Text(item.dtc.code, fontWeight = FontWeight.Bold) },
                overlineContent = flagsText(item.dtc.status)?.let { t -> { Text(t) } },
                supportingContent = { DtcInfoText(item.info) },
            )
        }
        item(key = "clear-note") {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val note = if (row.emission) R.string.modules_clear_emission_hint else R.string.modules_clear_not_supported
            Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun flagsText(status: Set<ModuleDtcStatus>): String? {
    val parts = ModuleDtcStatus.entries.filter { it in status }.map { s ->
        stringResource(
            when (s) {
                ModuleDtcStatus.TEST_FAILED -> R.string.modules_status_failed
                ModuleDtcStatus.PENDING -> R.string.modules_status_pending
                ModuleDtcStatus.CONFIRMED -> R.string.modules_status_confirmed
                ModuleDtcStatus.WARNING_LAMP -> R.string.modules_status_lamp
            },
        )
    }
    if (parts.isEmpty()) return null
    return parts.reduce { a, b -> stringResource(CoreUiR.string.common_join_dot, a, b) }
}

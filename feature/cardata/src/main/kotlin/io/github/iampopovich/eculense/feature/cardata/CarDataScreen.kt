package io.github.iampopovich.eculense.feature.cardata

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.navigation.ConnectDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.obd.Pid
import io.github.iampopovich.eculense.core.obdtext.ObdText
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import org.koin.compose.viewmodel.koinViewModel
import java.util.Locale

@Composable
internal fun CarDataRoute(viewModel: CarDataViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    CarDataScreen(
        state = state,
        onConnect = { navigator.navigate(ConnectDestination) },
        onTogglePanel = viewModel::togglePanel,
        onMoveUp = viewModel::moveUp,
        onMoveDown = viewModel::moveDown,
        onEditing = viewModel::setEditing,
        onKeepScreenOn = viewModel::setKeepScreenOn,
        onExtended = viewModel::setExtended,
        onExtendedPanel = viewModel::toggleExtendedPanel,
        onCheckYear = viewModel::checkModelYear,
    )
}

@Composable
internal fun CarDataScreen(
    state: CarDataUiState,
    onConnect: () -> Unit,
    onTogglePanel: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onEditing: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onExtended: (Boolean) -> Unit = {},
    onExtendedPanel: (String) -> Unit = {},
    onCheckYear: () -> Unit = {},
) {
    if (!state.connected) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.cardata_not_connected), textAlign = TextAlign.Center)
                Button(onClick = onConnect) { Text(stringResource(R.string.cardata_connect)) }
            }
        }
        return
    }
    var unsupportedOpen by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
        // Header actions on their own rows (large font: no long button next to a weight(1f) headline).
        item(key = "edit") {
            FilledTonalButton(onClick = { onEditing(!state.editing) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(stringResource(if (state.editing) R.string.cardata_done else R.string.cardata_edit_panel))
            }
        }
        if (state.editing) {
            panelEditor(state.panel, onTogglePanel, onMoveUp, onMoveDown, state.keepScreenOn, onKeepScreenOn, state.extended.panel, onExtendedPanel)
        } else {
            for (group in state.groups) {
                item(key = "g-${group.system}") {
                    Text(
                        ObdText.systemName(group.system).asString(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(group.rows, key = { "r-${it.pid.code}" }) { row -> ValueRow(row, onTogglePanel) }
            }
            if (state.unsupported.isNotEmpty()) {
                item(key = "unsupported") {
                    HorizontalDivider(Modifier.padding(top = 12.dp))
                    Text(
                        stringResource(
                            if (unsupportedOpen) CoreUiR.string.common_expander_open else CoreUiR.string.common_expander_closed,
                            stringResource(R.string.cardata_unsupported, state.unsupported.size),
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { unsupportedOpen = !unsupportedOpen }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                if (unsupportedOpen) {
                    items(state.unsupported, key = { "u-${it.code}" }) { pid ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(pidName(pid), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.cardata_na), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            // R-E: extended OBDb sensors (off by default) and the EV battery card.
            extendedSection(state.extended, onExtended, onExtendedPanel, onCheckYear)
        }
    }
}

@Composable
private fun pidName(pid: Pid): String = ObdText.pidNameRes(pid.code)?.let { stringResource(it) } ?: pid.sensorId

@Composable
private fun ValueRow(row: CarDataRow, onTogglePanel: (Int) -> Unit) {
    val value = row.value
    val text = when {
        row.notAnswering || value == null -> "—"
        row.pid.code == 0x03 -> ObdText.fuelSystemStatus(value).asString()
        else -> (if (row.estimated) "~" else "") + formatValue(value, decimalsFor(row.pid.code)) + (if (row.unit.isEmpty()) "" else " ${row.unit}")
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(pidName(row.pid), style = MaterialTheme.typography.bodyLarge)
            val note = when {
                row.notAnswering -> stringResource(R.string.cardata_not_answering)
                row.estimated -> stringResource(R.string.cardata_estimated)
                else -> null
            }
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
        IconToggleButton(checked = row.onPanel, onCheckedChange = { onTogglePanel(row.pid.code) }) {
            Icon(
                painterResource(if (row.onPanel) R.drawable.ic_star else R.drawable.ic_star_outline),
                contentDescription = stringResource(if (row.onPanel) R.string.cardata_remove_from_panel else R.string.cardata_add_to_panel),
                tint = if (row.onPanel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LazyListScope.panelEditor(
    panel: List<Pid>,
    onTogglePanel: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOn: (Boolean) -> Unit,
    extendedPanel: List<io.github.iampopovich.eculense.core.data.ExtendedSignal> = emptyList(),
    onExtendedPanel: (String) -> Unit = {},
) {
    item(key = "keep") {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = keepScreenOn, role = Role.Switch, onValueChange = onKeepScreenOn)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.cardata_keep_screen_on), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(checked = keepScreenOn, onCheckedChange = null)
        }
        HorizontalDivider()
    }
    item(key = "panel-title") {
        Text(
            stringResource(R.string.cardata_panel_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    if (panel.isEmpty() && extendedPanel.isEmpty()) {
        item(key = "panel-empty") { Text(stringResource(R.string.cardata_panel_empty), Modifier.padding(16.dp)) }
    }
    items(panel, key = { "p-${it.code}" }) { pid ->
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(pidName(pid), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            IconButton(onClick = { onMoveUp(pid.code) }, enabled = pid != panel.first()) {
                Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = stringResource(R.string.cardata_move_up))
            }
            IconButton(onClick = { onMoveDown(pid.code) }, enabled = pid != panel.last()) {
                Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = stringResource(R.string.cardata_move_down))
            }
            IconButton(onClick = { onTogglePanel(pid.code) }) {
                Icon(painterResource(R.drawable.ic_star), contentDescription = stringResource(R.string.cardata_remove_from_panel), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
    // R-E follow-up: extended (OBDb) tiles come after the standard ones; only removal here (order = starring order).
    items(extendedPanel, key = { "pe-${it.sensorId}" }) { signal ->
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(signal.name, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.cardata_ext_badge), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onExtendedPanel(signal.sensorId) }) {
                Icon(painterResource(R.drawable.ic_star), contentDescription = stringResource(R.string.cardata_remove_from_panel), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun decimalsFor(code: Int): Int = when (code) {
    0x42, 0x10, 0x5E, 0x06, 0x07, 0x08, 0x09, 0x0E -> 1
    0x14, 0x15, 0x44 -> 2
    else -> 0
}

/** Plain helper: number formatting outside composables (lint NonObservableLocale). */
private fun formatValue(value: Double, decimals: Int): String = String.format(Locale.getDefault(), "%.${decimals}f", value)

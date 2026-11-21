package io.github.iampopovich.eculense.feature.connect

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ScanStart

/**
 * A: the «Bluetooth LE» section of the Connect screen: scan status (one line + a spinner), the found adapters and,
 * on request, the other BLE devices. Buttons sit on their own rows (large system fonts).
 */
internal fun LazyListScope.bleSection(
    section: BleSection,
    enabled: Boolean,
    onConnect: (AdapterEndpoint) -> Unit,
    onScan: () -> Unit,
    onAllow: () -> Unit,
    onShowAll: (Boolean) -> Unit,
) {
    item(key = "ble-header") {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.connect_ble_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            BleStatus(section, onScan, onAllow)
        }
    }
    items(section.rows, key = { "ble-" + it.endpoint.address }) { row ->
        ListItem(
            headlineContent = { Text(row.endpoint.label) },
            supportingContent = {
                Column {
                    Text(row.endpoint.address)
                    if (!row.likelyObd) Text(stringResource(R.string.connect_ble_not_obd), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (row.serviceOnly) Text(stringResource(R.string.connect_ble_maybe_not_obd), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (row.alsoClassic) Text(stringResource(R.string.connect_ble_dual_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            modifier = Modifier.clickable(enabled = enabled) { onConnect(row.endpoint) },
        )
    }
    if (section.hidden > 0 || section.showAll) {
        item(key = "ble-show-all") {
            TextButton(onClick = { onShowAll(!section.showAll) }) {
                Text(
                    if (section.showAll) stringResource(R.string.connect_ble_hide_others)
                    else stringResource(R.string.connect_ble_show_others, section.hidden),
                )
            }
        }
    }
}

@Composable
private fun BleStatus(section: BleSection, onScan: () -> Unit, onAllow: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        section.scanning -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.connect_ble_scanning), color = muted)
        }
        section.lastStart == ScanStart.NO_PERMISSION -> {
            val rationale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.connect_ble_permission_nearby else R.string.connect_ble_permission_location
            Text(stringResource(rationale), color = muted)
            OutlinedButton(onClick = onAllow) { Text(stringResource(R.string.connect_ble_allow)) }
        }
        section.lastStart == ScanStart.BLUETOOTH_OFF -> {
            Text(stringResource(R.string.connect_ble_bt_off), color = muted)
            TextButton(onClick = onScan) { Text(stringResource(R.string.connect_ble_rescan)) }
        }
        section.lastStart == ScanStart.UNSUPPORTED -> Text(stringResource(R.string.connect_ble_unsupported), color = muted)
        else -> {
            val count = section.rows.count { it.likelyObd }
            Text(
                if (count == 0) stringResource(R.string.connect_ble_none) else stringResource(R.string.connect_ble_found, count),
                color = muted,
            )
            TextButton(onClick = onScan) { Text(stringResource(R.string.connect_ble_rescan)) }
        }
    }
}

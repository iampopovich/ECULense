package io.github.iampopovich.eculense.feature.cardata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.iampopovich.eculense.core.data.ExtendedStatus
import io.github.iampopovich.eculense.core.model.UnitsSettings
import io.github.iampopovich.eculense.core.obdb.EvBattery
import java.util.Locale
import kotlin.math.abs

/**
 * R-E: the «Extended sensors (OBDb)» switch with an honest status line, the EV battery card and the values of the
 * extended sensors. Signal names come from OBDb as they are (reference data is shown in its source language).
 */
internal fun LazyListScope.extendedSection(
    ext: ExtendedUi,
    onExtended: (Boolean) -> Unit,
    onPanel: (String) -> Unit = {},
    onCheckYear: () -> Unit = {},
) {
    val units = ext.units
    item(key = "ext-switch") {
        HorizontalDivider(Modifier.padding(top = 12.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = ext.enabled, role = Role.Switch, onValueChange = onExtended)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.cardata_ext_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(checked = ext.enabled, onCheckedChange = null)
        }
    }
    item(key = "ext-status") {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val ready = ext.ready
            val line = when {
                ext.status is ExtendedStatus.NeedsProfile -> stringResource(R.string.cardata_ext_no_profile)
                ext.status is ExtendedStatus.NoSignalset -> stringResource(R.string.cardata_ext_no_signalset)
                ext.status is ExtendedStatus.NeedsYearCheck -> stringResource(R.string.cardata_ext_check_year)
                ext.status is ExtendedStatus.CheckingYear -> stringResource(R.string.cardata_ext_checking_year)
                ready?.protocolMismatch == true -> stringResource(R.string.cardata_ext_kline)
                ready != null -> stringResource(R.string.cardata_ext_counts, ready.signals.size, ready.commands)
                else -> stringResource(R.string.cardata_ext_hint)
            }
            Text(line, style = MaterialTheme.typography.bodyMedium, color = muted)
            if (ext.status is ExtendedStatus.NeedsYearCheck || ext.status is ExtendedStatus.CheckingYear) {
                // One tap, never automatic: the check goes to raw.githubusercontent.com.
                OutlinedButton(onClick = onCheckYear, enabled = ext.status is ExtendedStatus.NeedsYearCheck) {
                    Text(stringResource(R.string.cardata_ext_check_year_action))
                }
            }
            if (ready != null) {
                Text(stringResource(R.string.cardata_ext_source, ready.repo.substringAfter('/')), style = MaterialTheme.typography.bodySmall, color = muted)
                if (ready.yearUnknown) Text(stringResource(R.string.cardata_ext_unverified_year), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (!ext.enabled) return
    ext.ev?.let { ev -> item(key = "ext-ev") { EvCard(ev, units) } }
    items(ext.rows, key = { "x-" + it.signal.sensorId }) { row ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.signal.name, style = MaterialTheme.typography.bodyLarge)
                val note = if (row.notAnswering) stringResource(R.string.cardata_not_answering) else row.signal.path
                note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(row.value?.let { valueText(it, row.unit) } ?: "—", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
            IconToggleButton(checked = row.onPanel, onCheckedChange = { onPanel(row.signal.sensorId) }) {
                Icon(
                    painterResource(if (row.onPanel) R.drawable.ic_star else R.drawable.ic_star_outline),
                    contentDescription = stringResource(if (row.onPanel) R.string.cardata_remove_from_panel else R.string.cardata_add_to_panel),
                    tint = if (row.onPanel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (ext.waiting > 0) {
        item(key = "ext-waiting") {
            Text(
                stringResource(R.string.cardata_ext_waiting, ext.waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun EvCard(ev: EvBattery, units: UnitsSettings) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.cardata_ev_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            ev.socPct?.let { EvLine(stringResource(R.string.cardata_ev_soc), valueText(it, "%")) }
            ev.sohPct?.let { EvLine(stringResource(R.string.cardata_ev_soh), valueText(it, "%")) }
            ev.voltageV?.let { EvLine(stringResource(R.string.cardata_ev_voltage), valueText(it, "V")) }
            ev.currentA?.let { EvLine(stringResource(R.string.cardata_ev_current), valueText(it, "A")) }
            ev.powerKw?.let { EvLine(stringResource(R.string.cardata_ev_power), valueText(it, "kW")) }
            if (ev.cellMinV != null || ev.cellMaxV != null) {
                EvLine(stringResource(R.string.cardata_ev_cells), stringResource(R.string.cardata_ev_range, ev.cellMinV?.let { valueText(it, "V") } ?: "—", ev.cellMaxV?.let { valueText(it, "V") } ?: "—"))
            }
            ev.cellSpreadV?.let { EvLine(stringResource(R.string.cardata_ev_spread), valueText(it * 1_000, "mV")) }
            if (ev.tempMinC != null || ev.tempMaxC != null) {
                fun t(c: Double?) = c?.let { units.display(it, "°C") }?.let { (v, u) -> valueText(v, u) } ?: "—"
                EvLine(stringResource(R.string.cardata_ev_temp), stringResource(R.string.cardata_ev_range, t(ev.tempMinC), t(ev.tempMaxC)))
            }
        }
    }
}

@Composable
private fun EvLine(label: String, value: String) {
    // Label above value would waste height; a short label and the value fit one row even with a large font.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Number with as many decimals as its size needs (3.52 V, 24.5 %, 344 V) and the unit symbol. */
private fun valueText(v: Double, unit: String): String {
    val decimals = if (abs(v) < 10) 2 else 1
    val n = String.format(Locale.getDefault(), "%.${decimals}f", v)
    return if (unit.isEmpty()) n else "$n $unit"
}

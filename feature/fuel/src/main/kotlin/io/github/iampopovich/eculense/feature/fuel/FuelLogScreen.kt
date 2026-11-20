package io.github.iampopovich.eculense.feature.fuel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.analytics.FuelLog
import io.github.iampopovich.eculense.core.model.FuelFillUp
import io.github.iampopovich.eculense.core.ui.CarNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel
import java.time.ZoneId
import kotlin.math.abs
import io.github.iampopovich.eculense.core.ui.R as CoreUiR

@Composable
internal fun FuelLogRoute(viewModel: FuelLogViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            }
            viewModel.importCsv(text)
        }
    }
    FuelLogScreen(
        state, zone = ZoneId.systemDefault(),
        onNew = viewModel::newForm, onEdit = viewModel::formOf, onSave = viewModel::save, onDelete = viewModel::delete,
        onCorrection = viewModel::setCorrection, onImport = { picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "*/*")) },
        onMessageShown = viewModel::messageShown,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FuelLogScreen(
    state: FuelLogUiState,
    zone: ZoneId,
    onNew: () -> FillUpForm,
    onEdit: (FuelFillUp) -> FillUpForm,
    onSave: (FillUpForm) -> Boolean,
    onDelete: (Long) -> Unit,
    onCorrection: (Boolean) -> Unit,
    onImport: () -> Unit,
    onMessageShown: () -> Unit,
) {
    var form by remember { mutableStateOf<FillUpForm?>(null) }
    val message = state.message?.asString()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.fuel_title), style = MaterialTheme.typography.headlineSmall)
                state.car?.let { Text(CarNames.display(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        // Actions on their own row (large font: never next to the headline).
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { form = onNew() }, enabled = state.car != null, modifier = Modifier.heightIn(min = 48.dp).testTag("fuel-add")) {
                    Text(stringResource(R.string.fuel_add))
                }
                OutlinedButton(onClick = onImport, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.fuel_import)) }
            }
        }
        message?.let { m -> item { Text(m, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onMessageShown)) } }
        if (!state.loading) item { Summary(state) }
        if (!state.loading) item { Calibration(state, onCorrection) }
        if (!state.loading && state.fillUps.isEmpty()) {
            item { Text(stringResource(R.string.fuel_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 24.dp)) }
        }
        items(state.fillUps, key = { it.id }) { f -> FillUpRow(f, state, zone) { form = onEdit(f) } }
        item { Text(" ", Modifier.padding(bottom = 16.dp)) }
    }
    form?.let { f ->
        FillUpDialog(
            f, zone, state.nowMs, state.car?.currency, state.odometer?.estimated == true,
            onSave = { if (onSave(it)) form = null },
            onDelete = if (f.id != 0L) ({ onDelete(f.id); form = null }) else null,
            onDismiss = { form = null },
        )
    }
}

@Composable
private fun Summary(state: FuelLogUiState) {
    val intervals = state.insights?.intervals.orEmpty()
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.fuel_consumption_title), style = MaterialTheme.typography.titleMedium)
            val learned = FuelLog.learnedLPer100(intervals)
            if (learned == null) {
                Text(stringResource(R.string.fuel_consumption_none), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(stringResource(R.string.fuel_l_per_100, FuelFormat.number(learned, 1)), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.fuel_consumption_basis, minOf(intervals.size, FuelLog.LEARN_LAST)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val litres = state.fillUps.sumOf { it.litres }
            val spend = state.fillUps.sumOf { it.total ?: 0.0 }
            if (state.fillUps.isNotEmpty()) {
                Text(
                    stringResource(R.string.fuel_totals, state.fillUps.size, FuelFormat.number(litres, 1), FuelFormat.money(spend, state.car?.currency)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun Calibration(state: FuelLogUiState, onCorrection: (Boolean) -> Unit) {
    val c = state.insights?.calibration
    val on = state.insights?.correctionOn == true
    Card {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(stringResource(R.string.fuel_calibration_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            if (c == null) {
                Text(
                    stringResource(R.string.fuel_calibration_none), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else {
                val pct = FuelFormat.number(abs(c.deviationPercent), 0)
                val verdict = when {
                    abs(c.deviationPercent) < 1 -> stringResource(R.string.fuel_calibration_exact)
                    c.deviationPercent < 0 -> stringResource(R.string.fuel_calibration_low, pct)
                    else -> stringResource(R.string.fuel_calibration_high, pct)
                }
                Text(verdict, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                Text(
                    stringResource(
                        R.string.fuel_calibration_basis, FuelFormat.number(c.estimatedLPer100, 1), FuelFormat.number(c.realLPer100, 1),
                        FuelFormat.number(c.recordedKm, 0), c.intervals,
                    ),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.fuel_correction)) },
                supportingContent = { Text(stringResource(R.string.fuel_correction_hint)) },
                trailingContent = { Switch(checked = on, onCheckedChange = null, enabled = c != null) },
                modifier = Modifier.toggleable(on, enabled = c != null, role = Role.Switch) { onCorrection(it) },
            )
        }
    }
}

@Composable
private fun FillUpRow(f: FuelFillUp, state: FuelLogUiState, zone: ZoneId, onClick: () -> Unit) {
    val interval = state.closedBy[f.atMs]
    val parts = listOfNotNull(
        f.odometerKm?.let { stringResource(R.string.fuel_km, FuelFormat.number(it.toDouble(), 0)) },
        f.total?.let { FuelFormat.money(it, state.car?.currency) },
        f.pricePerL?.let { stringResource(R.string.fuel_price_per_l, FuelFormat.number(it, 2)) },
        f.station,
    )
    Column {
        ListItem(
            overlineContent = {
                Text(
                    listOfNotNull(
                        stringResource(if (f.fullTank) R.string.fuel_full else R.string.fuel_partial),
                        if (f.missedPrevious) stringResource(R.string.fuel_missed_short) else null,
                    ).joinToString(" · "),
                )
            },
            headlineContent = { Text("${FuelFormat.date(f.atMs, zone)} · ${stringResource(R.string.fuel_litres, FuelFormat.number(f.litres, 2))}") },
            supportingContent = { if (parts.isNotEmpty()) Text(parts.joinToString(" · ")) },
            trailingContent = { interval?.let { Text(stringResource(R.string.fuel_l_per_100, FuelFormat.number(it.lPer100Km, 1)), style = MaterialTheme.typography.labelLarge) } },
            modifier = Modifier.clickable(onClick = onClick),
        )
        HorizontalDivider()
    }
}

@Composable
private fun FillUpDialog(
    initial: FillUpForm,
    zone: ZoneId,
    nowMs: Long,
    currency: String?,
    odometerEstimated: Boolean,
    onSave: (FillUpForm) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var f by remember { mutableStateOf(initial) }
    val errors = f.errors(zone, nowMs)
    val computed = f.computed()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.id == 0L) R.string.fuel_add else R.string.fuel_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Field(f.date, R.string.fuel_field_date, FillUpError.DATE in errors, R.string.fuel_error_date, KeyboardType.Text, "fill-date") { f = f.copy(date = it) }
                Field(
                    f.odometer, R.string.fuel_field_odometer, FillUpError.ODOMETER in errors, R.string.fuel_error_odometer, KeyboardType.Number, "fill-odo",
                    hint = if (odometerEstimated && initial.id == 0L) R.string.fuel_odometer_estimated else R.string.fuel_odometer_hint,
                ) { f = f.copy(odometer = it) }
                Field(f.litres, R.string.fuel_field_litres, FillUpError.LITRES in errors, R.string.fuel_error_number, KeyboardType.Decimal, "fill-litres") { f = f.copy(litres = it) }
                Field(f.price, R.string.fuel_field_price, FillUpError.PRICE in errors, R.string.fuel_error_number, KeyboardType.Decimal, "fill-price") { f = f.copy(price = it) }
                Field(f.total, R.string.fuel_field_total, FillUpError.TOTAL in errors, R.string.fuel_error_number, KeyboardType.Decimal, "fill-total") { f = f.copy(total = it) }
                Text(
                    if (computed == null) stringResource(R.string.fuel_need_two)
                    else stringResource(
                        R.string.fuel_computed, FuelFormat.number(computed.first, 2), FuelFormat.number(computed.second, 2), FuelFormat.money(computed.third, currency),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (computed == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow(R.string.fuel_field_full, R.string.fuel_field_full_hint, f.fullTank) { f = f.copy(fullTank = it) }
                SwitchRow(R.string.fuel_field_missed, R.string.fuel_field_missed_hint, f.missedPrevious) { f = f.copy(missedPrevious = it) }
                Field(f.station, R.string.fuel_field_station, false, R.string.fuel_error_number, KeyboardType.Text, "fill-station") { f = f.copy(station = it) }
                onDelete?.let { TextButton(onClick = it) { Text(stringResource(CoreUiR.string.common_delete), color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(f) }, enabled = errors.isEmpty(), modifier = Modifier.testTag("fill-save")) { Text(stringResource(CoreUiR.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiR.string.common_cancel)) } },
    )
}

@Composable
internal fun Field(
    value: String, label: Int, error: Boolean, errorText: Int, keyboard: KeyboardType, tag: String, hint: Int? = null, onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, label = { Text(stringResource(label)) }, isError = error,
        supportingText = { if (error) Text(stringResource(errorText)) else hint?.let { Text(stringResource(it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun SwitchRow(title: Int, hint: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange)) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 8.dp))
    }
}


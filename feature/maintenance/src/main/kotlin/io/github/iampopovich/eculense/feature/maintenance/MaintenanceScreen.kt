package io.github.iampopovich.eculense.feature.maintenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import io.github.iampopovich.eculense.core.analytics.MaintenanceStatus
import io.github.iampopovich.eculense.core.analyticstext.AnalyticsText
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import io.github.iampopovich.eculense.core.ui.CarNames
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import org.koin.compose.viewmodel.koinViewModel
import java.time.ZoneId
import kotlin.math.roundToLong

@Composable
internal fun MaintenanceRoute(viewModel: MaintenanceViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MaintenanceScreen(
        state = state,
        zone = viewModel.zone,
        actions = MaintenanceActions(
            addOdometer = viewModel::addOdometer,
            deleteOdometer = { viewModel.deleteOdometer(it) },
            markDone = { id, km, at -> viewModel.markDone(id, km, at) },
            save = { viewModel.save(it) },
            delete = { viewModel.delete(it) },
            undo = viewModel::undo,
            dismissUndo = viewModel::dismissUndo,
        ),
    )
}

internal data class MaintenanceActions(
    val addOdometer: (Long) -> Unit = {},
    val deleteOdometer: (Long) -> Unit = {},
    val markDone: (itemId: Long, km: Long?, atMs: Long) -> Unit = { _, _, _ -> },
    val save: (MaintenanceItem) -> Unit = {},
    val delete: (MaintenanceItem) -> Unit = {},
    val undo: () -> Unit = {},
    val dismissUndo: () -> Unit = {},
)

private sealed interface Dialog {
    data object Odometer : Dialog
    data class Done(val due: MaintenanceDue) : Dialog
    data class Edit(val item: MaintenanceItem?) : Dialog
}

/**
 * Mileage and the list of maintenance tasks. Mileage cannot be read over OBD-II — entered manually, then estimated from trips («≈»).
 * Status is labeled with a word, not just a color; the «Done» action can be undone from the snackbar (guards against accidental taps).
 */
@Composable
internal fun MaintenanceScreen(state: MaintenanceUiState, zone: ZoneId, actions: MaintenanceActions, modifier: Modifier = Modifier) {
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val undoText = state.undo?.text?.asString()
    val undoLabel = stringResource(R.string.maint_undo)
    LaunchedEffect(state.undo) {
        if (state.undo == null || undoText == null) return@LaunchedEffect
        val r = snackbar.showSnackbar(undoText, actionLabel = undoLabel, duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) actions.undo() else actions.dismissUndo()
    }
    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.padding(top = 16.dp)) {
                    Text(stringResource(R.string.maint_hub_title), style = MaterialTheme.typography.headlineSmall)
                    state.car?.let { Secondary(CarNames.display(it)) }
                }
            }
            if (state.loading) return@LazyColumn
            item { OdometerCard(state, zone) { dialog = Dialog.Odometer } }
            item { Text(stringResource(R.string.maint_items), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
            if (state.items.isEmpty()) item { Secondary(stringResource(R.string.maint_empty)) }
            items(state.items, key = { it.item.id }) { d ->
                ItemCard(d, zone, onDone = { dialog = Dialog.Done(d) }, onEdit = { dialog = Dialog.Edit(d.item) })
            }
            item {
                OutlinedButton(onClick = { dialog = Dialog.Edit(null) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.maint_add)) }
            }
            item { Text(" ", Modifier.padding(bottom = 72.dp)) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
    when (val d = dialog) {
        Dialog.Odometer -> OdometerDialog(state, zone, onSave = { actions.addOdometer(it); dialog = null }, onDelete = { actions.deleteOdometer(it); dialog = null }, onDismiss = { dialog = null })
        is Dialog.Done -> DoneDialog(d.due, state, zone, onConfirm = { km, at -> actions.markDone(d.due.item.id, km, at); dialog = null }, onDismiss = { dialog = null })
        is Dialog.Edit -> EditDialog(d.item, onSave = { actions.save(it); dialog = null }, onDelete = { actions.delete(it); dialog = null }, onDismiss = { dialog = null })
        null -> Unit
    }
}

@Composable
private fun OdometerCard(state: MaintenanceUiState, zone: ZoneId, onEnter: () -> Unit) {
    val e = state.estimate
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.maint_odometer), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (e == null) {
                Text(stringResource(R.string.maint_odometer_not_set), style = MaterialTheme.typography.titleLarge)
                Secondary(stringResource(R.string.maint_odometer_hint))
            } else {
                Text((if (e.estimated) "≈ " else "") + MaintenanceFormat.km(e.km).asString(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("odometer"))
                val base = stringResource(R.string.maint_odometer_entered, MaintenanceFormat.km(e.baseKm).asString(), MaintenanceFormat.date(e.baseAtMs, zone))
                Secondary(if (e.estimated) stringResource(R.string.maint_odometer_entered_plus, base, MaintenanceFormat.addedKm(e.addedKm).asString()) else base)
            }
            FilledTonalButton(onClick = onEnter) { Text(stringResource(if (e == null) R.string.maint_odometer_enter else R.string.maint_odometer_update)) }
        }
    }
}

@Composable
private fun ItemCard(d: MaintenanceDue, zone: ZoneId, onDone: () -> Unit, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(MaintenanceFormat.title(d.item).asString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusChip(d.status)
            }
            Secondary(MaintenanceFormat.interval(d.item).asString())
            MaintenanceFormat.lastDone(d.item, zone)?.let { Secondary(it.asString()) }
            Text(MaintenanceFormat.remaining(d).map { it.asString() }.joinToString(MaintenanceFormat.SEPARATOR), style = MaterialTheme.typography.bodyMedium)
            MaintenanceFormat.note(d.item)?.let { Secondary(it.asString()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onDone) { Text(stringResource(R.string.maint_done)) }
                TextButton(onClick = onEdit) { Text(stringResource(CoreUiR.string.common_edit)) }
            }
        }
    }
}

@Composable
private fun StatusChip(s: MaintenanceStatus) {
    val c = MaterialTheme.colorScheme
    val (bg, fg) = when (s) {
        MaintenanceStatus.OVERDUE -> c.errorContainer to c.onErrorContainer
        MaintenanceStatus.SOON -> c.tertiaryContainer to c.onTertiaryContainer
        MaintenanceStatus.OK -> c.secondaryContainer to c.onSecondaryContainer
        MaintenanceStatus.UNKNOWN -> c.surfaceVariant to c.onSurfaceVariant
    }
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.small) {
        Text(stringResource(MaintenanceFormat.status(s)), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun OdometerDialog(state: MaintenanceUiState, zone: ZoneId, onSave: (Long) -> Unit, onDelete: (Long) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val km = MaintenanceFormat.parseKm(text)
    val last = state.odometerLog.lastOrNull()
    val lower = last != null && km != null && km < last.km
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.maint_odometer_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true, label = { Text(stringResource(R.string.maint_mileage_km)) },
                    isError = text.isNotBlank() && km == null,
                    supportingText = {
                        when {
                            text.isNotBlank() && km == null -> Text(stringResource(R.string.maint_odometer_invalid))
                            lower -> Text(stringResource(R.string.maint_odometer_lower, MaintenanceFormat.km(last!!.km).asString()))
                            else -> Text(stringResource(R.string.maint_date_today))
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.testTag("odometer-input"),
                )
                if (last != null) {
                    Secondary(stringResource(R.string.maint_odometer_last, MaintenanceFormat.km(last.km).asString(), MaintenanceFormat.date(last.atMs, zone)))
                    TextButton(onClick = { onDelete(last.id) }) { Text(stringResource(R.string.maint_odometer_delete_last)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { km?.let(onSave) }, enabled = km != null) { Text(stringResource(CoreUiR.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiR.string.common_cancel)) } },
    )
}

@Composable
private fun DoneDialog(d: MaintenanceDue, state: MaintenanceUiState, zone: ZoneId, onConfirm: (Long?, Long) -> Unit, onDismiss: () -> Unit) {
    var kmText by rememberSaveable { mutableStateOf(state.estimate?.km?.roundToLong()?.toString().orEmpty()) }
    var dateText by rememberSaveable { mutableStateOf(MaintenanceFormat.date(state.nowMs, zone)) }
    val km = MaintenanceFormat.parseKm(kmText)
    val at = MaintenanceFormat.parseDate(dateText, zone, state.nowMs)
    val kmOk = kmText.isBlank() || km != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.maint_done_title, MaintenanceFormat.title(d.item).asString())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = kmText, onValueChange = { kmText = it }, singleLine = true, label = { Text(stringResource(R.string.maint_mileage_km)) },
                    isError = !kmOk,
                    supportingText = {
                        Text(
                            stringResource(
                                when {
                                    !kmOk -> R.string.maint_done_km_invalid
                                    state.estimate?.estimated == true -> R.string.maint_done_km_estimated
                                    else -> R.string.maint_done_km_optional
                                },
                            ),
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.testTag("done-km"),
                )
                OutlinedTextField(
                    value = dateText, onValueChange = { dateText = it }, singleLine = true, label = { Text(stringResource(R.string.maint_done_date)) },
                    isError = at == null, supportingText = { if (at == null) Text(stringResource(R.string.maint_done_date_invalid)) },
                    modifier = Modifier.testTag("done-date"),
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (at != null && kmOk) onConfirm(km, at) }, enabled = at != null && kmOk) { Text(stringResource(R.string.maint_mark)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiR.string.common_cancel)) } },
    )
}

@Composable
private fun EditDialog(item: MaintenanceItem?, onSave: (MaintenanceItem) -> Unit, onDelete: (MaintenanceItem) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val templateTitle = item?.kind?.let { AnalyticsText.maintenanceKind(context, it) }
    val templateNote = item?.noteKind?.let { AnalyticsText.maintenanceNote(context, it) }
    var title by rememberSaveable { mutableStateOf(item?.title?.ifBlank { null } ?: templateTitle.orEmpty()) }
    var km by rememberSaveable { mutableStateOf(item?.intervalKm?.toString().orEmpty()) }
    var months by rememberSaveable { mutableStateOf(item?.intervalMonths?.toString().orEmpty()) }
    var note by rememberSaveable { mutableStateOf(item?.note ?: templateNote.orEmpty()) }
    val kmVal = MaintenanceFormat.parseInt(km, 1_000_000)
    val monthsVal = MaintenanceFormat.parseInt(months, 240)
    val kmOk = km.isBlank() || kmVal != null
    val monthsOk = months.isBlank() || monthsVal != null
    val valid = title.isNotBlank() && kmOk && monthsOk
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (item == null) R.string.maint_new_item else R.string.maint_edit_item)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text(stringResource(R.string.maint_name)) }, isError = title.isBlank(), modifier = Modifier.testTag("edit-title"))
                OutlinedTextField(
                    km, { km = it }, singleLine = true, label = { Text(stringResource(R.string.maint_interval_km_label)) }, isError = !kmOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.testTag("edit-km"),
                )
                OutlinedTextField(
                    months, { months = it }, singleLine = true, label = { Text(stringResource(R.string.maint_interval_months_label)) }, isError = !monthsOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.testTag("edit-months"),
                )
                OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.maint_note)) }, maxLines = 3)
                Secondary(stringResource(R.string.maint_interval_hint))
                if (item != null && (item.lastDoneKm != null || item.lastDoneAtMs != null)) {
                    // Marked «done» by mistake and the snackbar is already gone — revert to «not marked yet».
                    TextButton(onClick = { onSave(item.copy(lastDoneKm = null, lastDoneAtMs = null)) }) { Text(stringResource(R.string.maint_reset_done)) }
                }
                if (item != null) TextButton(onClick = { onDelete(item) }) { Text(stringResource(R.string.maint_delete_item), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val base = item ?: MaintenanceItem(carId = 0, title = "")
                    // An unchanged template name/note stays typed (follows the app language); edited text becomes the user's own.
                    val newTitle = title.trim().takeUnless { base.kind != null && it == templateTitle } ?: ""
                    val keepNoteKind = base.noteKind != null && note.trim() == templateNote
                    onSave(
                        base.copy(
                            title = newTitle, intervalKm = kmVal, intervalMonths = monthsVal,
                            note = if (keepNoteKind) null else note.trim().ifBlank { null },
                            noteKind = if (keepNoteKind) base.noteKind else null,
                        ),
                    )
                },
            ) { Text(stringResource(CoreUiR.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiR.string.common_cancel)) } },
    )
}

@Composable
private fun Secondary(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

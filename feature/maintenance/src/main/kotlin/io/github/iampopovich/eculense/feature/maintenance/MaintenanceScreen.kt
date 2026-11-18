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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import io.github.iampopovich.eculense.core.analytics.MaintenanceStatus
import io.github.iampopovich.eculense.core.model.MaintenanceItem
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
 * Пробег и список работ ТО. Пробег по OBD-II не читается — ввод вручную, дальше оценка по поездкам («≈»).
 * Статус подписан словом, а не только цветом; действие «Сделано» отменяется из снекбара (защита от случайного нажатия).
 */
@Composable
internal fun MaintenanceScreen(state: MaintenanceUiState, zone: ZoneId, actions: MaintenanceActions, modifier: Modifier = Modifier) {
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.undo) {
        val u = state.undo ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(u.text, actionLabel = "Отменить", duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) actions.undo() else actions.dismissUndo()
    }
    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.padding(top = 16.dp)) {
                    Text("Обслуживание", style = MaterialTheme.typography.headlineSmall)
                    state.car?.let { Secondary(it.name) }
                }
            }
            if (state.loading) return@LazyColumn
            item { OdometerCard(state, zone) { dialog = Dialog.Odometer } }
            item { Text("Работы", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
            if (state.items.isEmpty()) item { Secondary("Список работ пуст. Добавьте работу — название и интервал.") }
            items(state.items, key = { it.item.id }) { d ->
                ItemCard(d, zone, onDone = { dialog = Dialog.Done(d) }, onEdit = { dialog = Dialog.Edit(d.item) })
            }
            item {
                OutlinedButton(onClick = { dialog = Dialog.Edit(null) }, modifier = Modifier.fillMaxWidth()) { Text("Добавить работу") }
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
            Text("Пробег", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (e == null) {
                Text("Не указан", style = MaterialTheme.typography.titleLarge)
                Secondary("OBD-II не передаёт показания одометра. Введите пробег с приборной панели — дальше он будет расти по записанным поездкам.")
            } else {
                Text((if (e.estimated) "≈ " else "") + MaintenanceFormat.km(e.km), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("odometer"))
                val base = "Введено ${MaintenanceFormat.km(e.baseKm)} · ${MaintenanceFormat.date(e.baseAtMs, zone)}"
                Secondary(if (e.estimated) "$base, плюс ${MaintenanceFormat.addedKm(e.addedKm)} по записанным поездкам (оценка)" else base)
            }
            FilledTonalButton(onClick = onEnter) { Text(if (e == null) "Ввести пробег" else "Обновить пробег") }
        }
    }
}

@Composable
private fun ItemCard(d: MaintenanceDue, zone: ZoneId, onDone: () -> Unit, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusChip(d.status)
            }
            Secondary(MaintenanceFormat.interval(d.item))
            MaintenanceFormat.lastDone(d.item, zone)?.let { Secondary(it) }
            Text(MaintenanceFormat.remaining(d), style = MaterialTheme.typography.bodyMedium)
            d.item.note?.takeIf { it.isNotBlank() }?.let { Secondary(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onDone) { Text("Сделано") }
                TextButton(onClick = onEdit) { Text("Изменить") }
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
        Text(MaintenanceFormat.status(s), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
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
        title = { Text("Пробег по одометру") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Пробег, км") },
                    isError = text.isNotBlank() && km == null,
                    supportingText = {
                        when {
                            text.isNotBlank() && km == null -> Text("Целое число от 0 до 5 000 000")
                            lower -> Text("Меньше прошлой записи (${MaintenanceFormat.km(last!!.km)}) — проверьте")
                            else -> Text("Дата — сегодня")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.testTag("odometer-input"),
                )
                if (last != null) {
                    Secondary("Последняя запись: ${MaintenanceFormat.km(last.km)} · ${MaintenanceFormat.date(last.atMs, zone)}")
                    TextButton(onClick = { onDelete(last.id) }) { Text("Удалить последнюю запись") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { km?.let(onSave) }, enabled = km != null) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
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
        title = { Text("Сделано: ${d.item.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = kmText, onValueChange = { kmText = it }, singleLine = true, label = { Text("Пробег, км") },
                    isError = !kmOk,
                    supportingText = { Text(if (!kmOk) "Целое число или пусто" else if (state.estimate?.estimated == true) "Подставлена оценка — поправьте по одометру" else "Можно оставить пустым") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.testTag("done-km"),
                )
                OutlinedTextField(
                    value = dateText, onValueChange = { dateText = it }, singleLine = true, label = { Text("Дата (дд.мм.гггг)") },
                    isError = at == null, supportingText = { if (at == null) Text("Дата в формате дд.мм.гггг, не в будущем") },
                    modifier = Modifier.testTag("done-date"),
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (at != null && kmOk) onConfirm(km, at) }, enabled = at != null && kmOk) { Text("Отметить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun EditDialog(item: MaintenanceItem?, onSave: (MaintenanceItem) -> Unit, onDelete: (MaintenanceItem) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(item?.title.orEmpty()) }
    var km by rememberSaveable { mutableStateOf(item?.intervalKm?.toString().orEmpty()) }
    var months by rememberSaveable { mutableStateOf(item?.intervalMonths?.toString().orEmpty()) }
    var note by rememberSaveable { mutableStateOf(item?.note.orEmpty()) }
    val kmVal = MaintenanceFormat.parseInt(km, 1_000_000)
    val monthsVal = MaintenanceFormat.parseInt(months, 240)
    val kmOk = km.isBlank() || kmVal != null
    val monthsOk = months.isBlank() || monthsVal != null
    val valid = title.isNotBlank() && kmOk && monthsOk
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "Новая работа" else "Изменить работу") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("Название") }, isError = title.isBlank(), modifier = Modifier.testTag("edit-title"))
                OutlinedTextField(
                    km, { km = it }, singleLine = true, label = { Text("Интервал, км") }, isError = !kmOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.testTag("edit-km"),
                )
                OutlinedTextField(
                    months, { months = it }, singleLine = true, label = { Text("Интервал, месяцев") }, isError = !monthsOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.testTag("edit-months"),
                )
                OutlinedTextField(note, { note = it }, label = { Text("Заметка") }, maxLines = 3)
                Secondary("Пустой интервал — не считать по нему. Срабатывает то, что наступит раньше.")
                if (item != null && (item.lastDoneKm != null || item.lastDoneAtMs != null)) {
                    // Отметка «сделано» по ошибке, а снекбар уже ушёл, — вернуть «ещё не отмечали».
                    TextButton(onClick = { onSave(item.copy(lastDoneKm = null, lastDoneAtMs = null)) }) { Text("Сбросить отметку «сделано»") }
                }
                if (item != null) TextButton(onClick = { onDelete(item) }) { Text("Удалить работу", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val base = item ?: MaintenanceItem(carId = 0, title = "")
                    onSave(base.copy(title = title.trim(), intervalKm = kmVal, intervalMonths = monthsVal, note = note.trim().ifBlank { null }))
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun Secondary(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

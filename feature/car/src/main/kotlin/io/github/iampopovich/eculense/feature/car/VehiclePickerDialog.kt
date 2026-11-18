package io.github.iampopovich.eculense.feature.car

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.iampopovich.eculense.core.vehicledb.VehicleEngine

/** Полноэкранный пошаговый выбор из справочника: поиск, список, на последнем шаге — ТТХ и «Заполнить». */
@Composable
internal fun VehiclePickerDialog(
    state: PickerState,
    onSearch: (String) -> Unit,
    onChoose: (PickerOption) -> Unit,
    onBack: () -> Unit,
    onFill: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("Назад") }
                    Text(
                        if (state.engine != null) "Характеристики" else "Справочник: ${state.step.title.lowercase()}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClose) { Text("Закрыть") }
                }
                if (state.path.isNotEmpty()) Text(state.path, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val engine = state.engine
                if (engine != null) {
                    EngineCard(engine, onFill)
                } else {
                    OutlinedTextField(
                        value = state.query, onValueChange = onSearch, label = { Text("Поиск") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("picker-search"),
                    )
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!state.loading && state.options.isEmpty() && state.error == null) Text("Ничего не найдено")
                    LazyColumn(Modifier.weight(1f)) {
                        items(state.options, key = { it.id }) { o ->
                            ListItem(
                                headlineContent = { Text(o.title) },
                                supportingContent = o.subtitle?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                                modifier = Modifier.clickable { onChoose(o) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineCard(e: VehicleEngine, onFill: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(e.label, style = MaterialTheme.typography.titleLarge)
        specRows(e).forEach { (k, v) ->
            Row { Text("$k: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, style = MaterialTheme.typography.bodyMedium) }
        }
        Text(
            "Заполнятся объём, цилиндры, топливо, КПП, привод, бак и масса. Название, марка и год останутся вашими; всё можно поправить.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onFill, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Заполнить") }
        Text("Данные: gor3a/vehicle-makes-models (ODbL 1.0)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun specRows(e: VehicleEngine): List<Pair<String, String>> = listOfNotNull(
    e.displacementCc?.let { "Объём" to "$it см³ (${CarFormat.number(it / 1000.0)} л)" },
    e.cylinders?.let { "Цилиндров" to "$it" },
    e.powerHp?.let { "Мощность" to "${CarFormat.number(it, 0)} л. с." },
    e.torqueNm?.let { "Момент" to "${CarFormat.number(it, 0)} Н·м" },
    e.fuelType?.let { "Топливо" to (e.fuel?.label ?: it) },
    e.transmission?.let { "КПП" to "${e.gearbox?.label ?: ""} ($it)".trim() },
    e.drivetrain?.let { "Привод" to (e.drive?.label ?: it) },
    e.tankL?.let { "Бак" to "${CarFormat.number(it)} л" },
    e.curbWeightKg?.let { "Масса" to "$it кг" },
    e.fuelEconomyL100?.let { "Расход (смеш.)" to "${CarFormat.number(it)} л/100 км" },
)

package io.github.iampopovich.eculense.feature.car

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.model.DriveType
import io.github.iampopovich.eculense.core.model.FuelType
import io.github.iampopovich.eculense.core.model.Gearbox
import io.github.iampopovich.eculense.core.ui.aboveKeyboard
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun CarEditRoute(carId: Long, onClose: () -> Unit, viewModel: CarEditViewModel = koinViewModel(key = "car-edit-$carId") { parametersOf(carId) }) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onClose() }
    CarEditScreen(
        state, onEdit = viewModel::edit, onSave = viewModel::save, onDelete = viewModel::delete,
        onOpenPicker = viewModel::openPicker, onDownloadCatalog = { viewModel.downloadCatalog() },
    )
    state.picker?.let { p ->
        VehiclePickerDialog(p, viewModel::search, viewModel::choose, viewModel::pickerBack, viewModel::fillFromPicker, viewModel::closePicker)
    }
    if (state.confirmMeteredDownload) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDownload,
            title = { Text("Скачать по мобильной сети?") },
            text = { Text("Справочник моделей — около 57 МБ. Wi-Fi не подключён, трафик может быть платным.") },
            confirmButton = { TextButton(onClick = { viewModel.downloadCatalog(confirmed = true) }) { Text("Скачать") } },
            dismissButton = { TextButton(onClick = viewModel::dismissDownload) { Text("Отмена") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CarEditScreen(
    state: CarEditUiState,
    onEdit: ((CarForm) -> CarForm) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPicker: () -> Unit = {},
    onDownloadCatalog: () -> Unit = {},
) {
    if (state.loading) return
    val f = state.form
    val e = state.errors
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().aboveKeyboard().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (state.isNew) "Новая машина" else "Профиль машины",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        Section("Основное")
        Field("Название *", f.name, e[CarField.NAME], "name", capitalize = true) { v -> onEdit { it.copy(name = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Марка", f.brand, null, "brand", Modifier.weight(1f), capitalize = true) { v -> onEdit { it.copy(brand = v) } }
            Field("Модель", f.model, null, "model", Modifier.weight(1f), capitalize = true) { v -> onEdit { it.copy(model = v) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Поколение", f.generation, null, "generation", Modifier.weight(1f), capitalize = true) { v -> onEdit { it.copy(generation = v) } }
            Field("Год", f.year, e[CarField.YEAR], "year", Modifier.weight(1f), KeyboardType.Number) { v -> onEdit { it.copy(year = v) } }
        }
        CatalogBlock(state, onOpenPicker, onDownloadCatalog)

        Section("Двигатель и КПП")
        Field("Код двигателя", f.engineCode, null, "engineCode", capitalize = true) { v -> onEdit { it.copy(engineCode = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Объём, л", f.engineVolumeL, e[CarField.ENGINE_VOLUME], "engineVolume", Modifier.weight(1f), KeyboardType.Decimal) { v ->
                onEdit { it.copy(engineVolumeL = v) }
            }
            Field("Цилиндров", f.cylinders, e[CarField.CYLINDERS], "cylinders", Modifier.weight(1f), KeyboardType.Number) { v ->
                onEdit { it.copy(cylinders = v) }
            }
        }
        Label("Топливо")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FuelType.entries.forEach { t ->
                FilterChip(
                    selected = f.fuelType == t,
                    onClick = { onEdit { it.copy(fuelType = if (it.fuelType == t) null else t) } },
                    label = { Text(t.label) },
                )
            }
        }
        Label("Коробка передач")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Gearbox.entries.forEach { g ->
                FilterChip(
                    selected = f.gearbox == g,
                    onClick = { onEdit { it.copy(gearbox = if (it.gearbox == g) null else g) } },
                    label = { Text(g.label) },
                )
            }
        }
        Label("Привод")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DriveType.entries.forEach { d ->
                FilterChip(
                    selected = f.drive == d,
                    onClick = { onEdit { it.copy(drive = if (it.drive == d) null else d) } },
                    label = { Text(d.label) },
                )
            }
        }

        Section("Эксплуатация")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Бак, л", f.tankVolumeL, e[CarField.TANK], "tank", Modifier.weight(1f), KeyboardType.Decimal) { v -> onEdit { it.copy(tankVolumeL = v) } }
            Field("Масса, кг", f.curbWeightKg, e[CarField.MASS], "mass", Modifier.weight(1f), KeyboardType.Number) { v -> onEdit { it.copy(curbWeightKg = v) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Пробег, км", f.odometerKm, e[CarField.ODOMETER], "odometer", Modifier.weight(1f), KeyboardType.Number) { v ->
                onEdit { it.copy(odometerKm = v) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Цена топлива за литр", f.fuelPrice, e[CarField.FUEL_PRICE], "fuelPrice", Modifier.weight(1f), KeyboardType.Decimal) { v ->
                onEdit { it.copy(fuelPrice = v) }
            }
            Field("Валюта", f.currency, e[CarField.CURRENCY], "currency", Modifier.weight(0.6f), capitalize = true) { v ->
                onEdit { it.copy(currency = v) }
            }
        }
        Field(
            "VIN (необязательно)", f.vin, e[CarField.VIN], "vin", capitalize = true,
            hint = "Хранится только на телефоне и не передаётся ИИ",
        ) { v -> onEdit { it.copy(vin = v) } }

        Section("Интервалы ТО, км")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Масло", f.oilKm, e[CarField.OIL], "oil", Modifier.weight(1f), KeyboardType.Number) { v -> onEdit { it.copy(oilKm = v) } }
            Field("Свечи", f.sparkPlugsKm, e[CarField.PLUGS], "plugs", Modifier.weight(1f), KeyboardType.Number) { v -> onEdit { it.copy(sparkPlugsKm = v) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Возд. фильтр", f.airFilterKm, e[CarField.AIR_FILTER], "airFilter", Modifier.weight(1f), KeyboardType.Number) { v ->
                onEdit { it.copy(airFilterKm = v) }
            }
            Field("Топл. фильтр", f.fuelFilterKm, e[CarField.FUEL_FILTER], "fuelFilter", Modifier.weight(1f), KeyboardType.Number) { v ->
                onEdit { it.copy(fuelFilterKm = v) }
            }
        }

        Button(onClick = onSave, enabled = e.isEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Сохранить") }
        if (e.isNotEmpty()) {
            Text("Исправьте поля с ошибками, чтобы сохранить", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (state.canDelete) {
            TextButton(onClick = { confirmDelete = true }) { Text("Удалить машину", color = MaterialTheme.colorScheme.error) }
        }
        Text(" ", Modifier.padding(bottom = 16.dp))
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить «${f.name}»?") },
            text = { Text("Удалятся профиль и поездки этой машины. Записи датчиков останутся, но без привязки к машине.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
}

/** Model reference database: pick (if downloaded), otherwise a hint with a download button; manual entry is always available. */
@Composable
private fun CatalogBlock(state: CarEditUiState, onOpenPicker: () -> Unit, onDownload: () -> Unit) {
    val pack = state.vehiclePack
    when {
        state.catalogAvailable -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = onOpenPicker, modifier = Modifier.fillMaxWidth()) { Text("Заполнить из справочника") }
            Text("Марка → модель → поколение → модификация; поля остаются редактируемыми.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        pack?.downloading == true -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val p = pack.progress ?: -1
            Text(if (p >= 0) "Справочник моделей скачивается: $p %" else "Справочник моделей скачивается…", style = MaterialTheme.typography.bodyMedium)
            if (p >= 0) LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        pack != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Справочник моделей не скачан (~57 МБ). С ним объём, бак, масса и КПП заполнятся сами; без него — вручную.",
                style = MaterialTheme.typography.bodyMedium,
            )
            pack.error?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = onDownload) { Text("Скачать справочник") }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Field(
    label: String,
    value: String,
    error: String?,
    tag: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboard: KeyboardType = KeyboardType.Text,
    capitalize: Boolean = false,
    hint: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = (error ?: hint)?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
        ),
        modifier = modifier.testTag("car-$tag"),
    )
}

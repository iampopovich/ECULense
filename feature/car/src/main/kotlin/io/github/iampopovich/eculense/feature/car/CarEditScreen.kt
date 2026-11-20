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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.model.DriveType
import io.github.iampopovich.eculense.core.model.FuelType
import io.github.iampopovich.eculense.core.model.Gearbox
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import io.github.iampopovich.eculense.core.ui.UiText
import io.github.iampopovich.eculense.core.ui.VinPrivacy
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
            title = { Text(stringResource(R.string.car_metered_title)) },
            text = { Text(stringResource(R.string.car_metered_text)) },
            confirmButton = { TextButton(onClick = { viewModel.downloadCatalog(confirmed = true) }) { Text(stringResource(R.string.car_download)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissDownload) { Text(stringResource(CoreUiR.string.common_cancel)) } },
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
            stringResource(if (state.isNew) R.string.car_new else R.string.car_profile),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        Section(stringResource(R.string.car_section_main))
        Field(stringResource(R.string.car_field_name), f.name, e[CarField.NAME], "name", capitalize = true) { v -> onEdit { it.copy(name = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.car_field_brand), f.brand, null, "brand", Modifier.weight(1f), capitalize = true) { v -> onEdit { it.copy(brand = v) } }
            Field(stringResource(R.string.car_field_model), f.model, null, "model", Modifier.weight(1f), capitalize = true) { v -> onEdit { it.copy(model = v) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.car_field_generation), f.generation, null, "generation", Modifier.weight(1f), capitalize = true) { v -> onEdit { it.copy(generation = v) } }
            Field(stringResource(R.string.car_field_year), f.year, e[CarField.YEAR], "year", Modifier.weight(1f), KeyboardType.Number) { v -> onEdit { it.copy(year = v) } }
        }
        CatalogBlock(state, onOpenPicker, onDownloadCatalog)

        Section(stringResource(R.string.car_section_engine))
        Field(stringResource(R.string.car_field_engine_code), f.engineCode, null, "engineCode", capitalize = true) { v -> onEdit { it.copy(engineCode = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.car_field_volume), f.engineVolumeL, e[CarField.ENGINE_VOLUME], "engineVolume", Modifier.weight(1f), KeyboardType.Decimal) { v ->
                onEdit { it.copy(engineVolumeL = v) }
            }
            Field(stringResource(R.string.car_field_cylinders), f.cylinders, e[CarField.CYLINDERS], "cylinders", Modifier.weight(1f), KeyboardType.Number) { v ->
                onEdit { it.copy(cylinders = v) }
            }
        }
        Label(stringResource(R.string.car_label_fuel))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FuelType.entries.forEach { t ->
                FilterChip(
                    selected = f.fuelType == t,
                    onClick = { onEdit { it.copy(fuelType = if (it.fuelType == t) null else t) } },
                    label = { Text(stringResource(t.labelRes)) },
                )
            }
        }
        Label(stringResource(R.string.car_label_gearbox))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Gearbox.entries.forEach { g ->
                FilterChip(
                    selected = f.gearbox == g,
                    onClick = { onEdit { it.copy(gearbox = if (it.gearbox == g) null else g) } },
                    label = { Text(stringResource(g.labelRes)) },
                )
            }
        }
        Label(stringResource(R.string.car_label_drive))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DriveType.entries.forEach { d ->
                FilterChip(
                    selected = f.drive == d,
                    onClick = { onEdit { it.copy(drive = if (it.drive == d) null else d) } },
                    label = { Text(stringResource(d.labelRes)) },
                )
            }
        }

        Section(stringResource(R.string.car_section_usage))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.car_field_tank), f.tankVolumeL, e[CarField.TANK], "tank", Modifier.weight(1f), KeyboardType.Decimal) { v -> onEdit { it.copy(tankVolumeL = v) } }
            Field(stringResource(R.string.car_field_mass), f.curbWeightKg, e[CarField.MASS], "mass", Modifier.weight(1f), KeyboardType.Number) { v -> onEdit { it.copy(curbWeightKg = v) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.car_field_odometer), f.odometerKm, e[CarField.ODOMETER], "odometer", Modifier.weight(1f), KeyboardType.Number) { v ->
                onEdit { it.copy(odometerKm = v) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.car_field_fuel_price), f.fuelPrice, e[CarField.FUEL_PRICE], "fuelPrice", Modifier.weight(1f), KeyboardType.Decimal) { v ->
                onEdit { it.copy(fuelPrice = v) }
            }
            Field(stringResource(R.string.car_field_currency), f.currency, e[CarField.CURRENCY], "currency", Modifier.weight(0.6f), capitalize = true) { v ->
                onEdit { it.copy(currency = v) }
            }
        }
        VinField(
            stringResource(R.string.car_field_vin), f.vin, e[CarField.VIN],
            hint = stringResource(R.string.car_vin_hint),
        ) { v -> onEdit { it.copy(vin = v) } }

        Section(stringResource(R.string.car_section_service))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.car_field_oil), f.oilKm, e[CarField.OIL], "oil", Modifier.weight(1f), KeyboardType.Number) { v -> onEdit { it.copy(oilKm = v) } }
            Field(stringResource(R.string.car_field_plugs), f.sparkPlugsKm, e[CarField.PLUGS], "plugs", Modifier.weight(1f), KeyboardType.Number) { v -> onEdit { it.copy(sparkPlugsKm = v) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.car_field_air_filter), f.airFilterKm, e[CarField.AIR_FILTER], "airFilter", Modifier.weight(1f), KeyboardType.Number) { v ->
                onEdit { it.copy(airFilterKm = v) }
            }
            Field(stringResource(R.string.car_field_fuel_filter), f.fuelFilterKm, e[CarField.FUEL_FILTER], "fuelFilter", Modifier.weight(1f), KeyboardType.Number) { v ->
                onEdit { it.copy(fuelFilterKm = v) }
            }
        }

        Button(onClick = onSave, enabled = e.isEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(CoreUiR.string.common_save)) }
        if (e.isNotEmpty()) {
            Text(stringResource(R.string.car_fix_errors), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (state.canDelete) {
            TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.car_delete), color = MaterialTheme.colorScheme.error) }
        }
        Text(" ", Modifier.padding(bottom = 16.dp))
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.car_delete_title, f.name)) },
            text = { Text(stringResource(R.string.car_delete_text)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(CoreUiR.string.common_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(CoreUiR.string.common_cancel)) } },
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
            OutlinedButton(onClick = onOpenPicker, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.car_catalog_fill)) }
            Text(stringResource(R.string.car_catalog_fill_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        pack?.downloading == true -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val p = pack.progress ?: -1
            Text(if (p >= 0) stringResource(R.string.car_catalog_downloading_percent, p) else stringResource(R.string.car_catalog_downloading), style = MaterialTheme.typography.bodyMedium)
            if (p >= 0) LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        pack != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.car_catalog_missing),
                style = MaterialTheme.typography.bodyMedium,
            )
            pack.error?.let { Text(stringResource(R.string.car_catalog_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = onDownload) { Text(stringResource(R.string.car_catalog_download)) }
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
    error: UiText?,
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
        supportingText = (error?.asString() ?: hint)?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
        ),
        modifier = modifier.testTag("car-$tag"),
    )
}

/** C14: the VIN is drawn as bullets until «Show» is tapped (reset when the screen is left). */
@Composable
private fun VinField(label: String, value: String, error: UiText?, hint: String?, onChange: (String) -> Unit) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = (error?.asString() ?: hint)?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (shown) VisualTransformation.None else VinPrivacy.fieldMask,
        trailingIcon = {
            TextButton(onClick = { shown = !shown }) {
                Text(stringResource(if (shown) CoreUiR.string.common_vin_hide else CoreUiR.string.common_vin_show))
            }
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth().testTag("car-vin"),
    )
}

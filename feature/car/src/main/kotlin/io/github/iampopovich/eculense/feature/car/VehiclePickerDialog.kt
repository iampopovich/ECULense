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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import io.github.iampopovich.eculense.core.vehicledb.VehicleEngine

/** Full-screen step-by-step picker from the reference database: search, list, and on the last step specs and «Fill in». */
@Composable
internal fun VehiclePickerDialog(
    state: PickerState,
    onSearch: (String) -> Unit,
    onChoose: (PickerOption) -> Unit,
    onBack: () -> Unit,
    onFill: () -> Unit,
    onClose: () -> Unit,
) {
    // the search field opens the keyboard; after «Fill in»/«Close» it must not stay over the form
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text(stringResource(CoreUiR.string.common_back)) }
                    Text(
                        if (state.engine != null) stringResource(R.string.car_picker_specs) else stringResource(R.string.car_picker_title, stringResource(state.step.title).lowercase()),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClose) { Text(stringResource(R.string.car_picker_close)) }
                }
                if (state.path.isNotEmpty()) Text(state.path, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                val engine = state.engine
                if (engine != null) {
                    EngineCard(engine, onFill)
                } else {
                    OutlinedTextField(
                        value = state.query, onValueChange = onSearch, label = { Text(stringResource(R.string.car_picker_search)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("picker-search"),
                    )
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!state.loading && state.options.isEmpty() && state.error == null) Text(stringResource(R.string.car_picker_nothing))
                    LazyColumn(Modifier.weight(1f)) {
                        items(state.options, key = { it.id }) { o ->
                            ListItem(
                                headlineContent = { Text(o.title) },
                                supportingContent = o.subtitle?.asString()?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
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
            stringResource(R.string.car_picker_fill_hint),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onFill, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.car_picker_fill)) }
        Text(stringResource(R.string.car_picker_source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun specRows(e: VehicleEngine): List<Pair<String, String>> = listOfNotNull(
    e.displacementCc?.let { stringResource(R.string.car_spec_volume) to stringResource(R.string.car_spec_volume_value, it, CarFormat.number(it / 1000.0)) },
    e.cylinders?.let { stringResource(R.string.car_spec_cylinders) to "$it" },
    e.powerHp?.let { stringResource(R.string.car_spec_power) to stringResource(R.string.car_spec_power_value, CarFormat.number(it, 0)) },
    e.torqueNm?.let { stringResource(R.string.car_spec_torque) to stringResource(R.string.car_spec_torque_value, CarFormat.number(it, 0)) },
    e.fuelType?.let { stringResource(R.string.car_spec_fuel) to (e.fuel?.let { f -> stringResource(f.labelRes) } ?: it) },
    e.transmission?.let { stringResource(R.string.car_spec_gearbox) to "${e.gearbox?.let { g -> stringResource(g.labelRes) } ?: ""} ($it)".trim() },
    e.drivetrain?.let { stringResource(R.string.car_spec_drive) to (e.drive?.let { d -> stringResource(d.labelRes) } ?: it) },
    e.tankL?.let { stringResource(R.string.car_spec_tank) to stringResource(R.string.car_spec_liters_value, CarFormat.number(it)) },
    e.curbWeightKg?.let { stringResource(R.string.car_spec_mass) to stringResource(R.string.car_spec_mass_value, it) },
    e.fuelEconomyL100?.let { stringResource(R.string.car_spec_economy) to stringResource(R.string.car_spec_economy_value, CarFormat.number(it)) },
)

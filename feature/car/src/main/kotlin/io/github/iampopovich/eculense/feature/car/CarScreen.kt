package io.github.iampopovich.eculense.feature.car

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import io.github.iampopovich.eculense.core.analytics.ObdCompatibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.UnitsSettings
import io.github.iampopovich.eculense.core.navigation.CarEditDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.ui.CarNames
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import org.koin.compose.viewmodel.koinViewModel
import io.github.iampopovich.eculense.core.ui.EcuDates

@Composable
internal fun CarRoute(viewModel: CarViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    CarScreen(
        state = state,
        onEdit = { navigator.navigate(CarEditDestination(it)) },
        onAdd = { navigator.navigate(CarEditDestination(0)) },
        onSelect = viewModel::setActive,
        onUnits = viewModel::setUnits,
    )
}

@Composable
internal fun CarScreen(
    state: CarUiState,
    onEdit: (Long) -> Unit,
    onAdd: () -> Unit,
    onSelect: (Long) -> Unit,
    onUnits: (UnitsSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.car_hub_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp)) }
        if (state.loading) return@LazyColumn
        state.active?.let { car -> item { ActiveCarCard(car, onEdit = { onEdit(car.id) }) } }
        item { SectionTitle(stringResource(R.string.car_section_cars)) }
        items(state.cars, key = { it.id }) { car ->
            CarRow(car, selected = car.id == state.active?.id, onSelect = { onSelect(car.id) }, onEdit = { onEdit(car.id) })
        }
        item { OutlinedButton(onClick = onAdd) { Text(stringResource(R.string.car_add)) } }
        item { SectionTitle(stringResource(R.string.car_section_units)) }
        item { UnitsCard(state.units, onUnits) }
        item { Text(" ", Modifier.padding(bottom = 8.dp)) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ActiveCarCard(car: CarProfile, onEdit: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(CarNames.display(car), style = MaterialTheme.typography.titleLarge)
            CarFormat.title(car).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            CarFormat.engine(car).takeIf { it.isNotBlank() }?.let { Secondary(it) }
            car.fuelPrice?.let { Secondary(stringResource(R.string.car_fuel_price_line, CarFormat.number(it, 2), car.currency.orEmpty())) }
            car.tankVolumeL?.let { Secondary(stringResource(R.string.car_tank_line, CarFormat.number(it, 0))) }
            car.odometerKm?.let { km ->
                val value = CarFormat.number(km.toDouble(), 0)
                val at = car.odometerAtMs
                Secondary(if (at != null) stringResource(R.string.car_odometer_line_date, value, EcuDates.date(at)) else stringResource(R.string.car_odometer_line, value))
            }
            car.diagProfile?.let { Secondary(stringResource(R.string.car_diag_profile_line, it)) }
            if (car.brand == null && car.model == null && car.year == null) {
                Secondary(stringResource(R.string.car_fill_profile_hint))
            }
            CompatibilityNote(car)
            FilledTonalButton(onClick = onEdit, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(CoreUiR.string.common_edit)) }
        }
    }
}

@Composable
private fun Secondary(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** C12: non-blocking note when the model year predates mandatory OBD-II/EOBD in the phone's country. */
@Composable
private fun CompatibilityNote(car: CarProfile) {
    // The phone's region, not the in-app language: the mandate depends on where the car was sold.
    val country = remember { android.content.res.Resources.getSystem().configuration.locales[0]?.country.orEmpty() }
    val hint = ObdCompatibility.hint(car.year, car.fuelType, country) ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.padding(top = 4.dp)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.car_obd_compat_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (hint.lateAdoption) {
                Text(stringResource(R.string.car_obd_compat_late), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun CarRow(car: CarProfile, selected: Boolean, onSelect: () -> Unit, onEdit: () -> Unit) {
    ListItem(
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(CarNames.display(car)) },
        supportingContent = {
            Text(if (selected) stringResource(R.string.car_active_hint) else CarFormat.title(car).ifBlank { stringResource(R.string.car_tap_to_select) })
        },
        trailingContent = {
            IconButton(onClick = onEdit) { Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.car_edit_named, CarNames.display(car))) }
        },
        modifier = Modifier.selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    )
}

@Composable
private fun UnitsCard(units: UnitsSettings, onUnits: (UnitsSettings) -> Unit) {
    Card {
        Column(Modifier.padding(vertical = 4.dp)) {
            UnitSwitch(R.string.car_units_km, R.string.car_units_km_off, units.metricDistance) { onUnits(units.copy(metricDistance = it)) }
            UnitSwitch(R.string.car_units_celsius, R.string.car_units_celsius_off, units.celsius) { onUnits(units.copy(celsius = it)) }
            UnitSwitch(R.string.car_units_l100, R.string.car_units_l100_off, units.litersPer100Km) { onUnits(units.copy(litersPer100Km = it)) }
            UnitSwitch(R.string.car_units_liters, R.string.car_units_liters_off, units.liters) { onUnits(units.copy(liters = it)) }
            UnitSwitch(R.string.car_units_kpa, R.string.car_units_kpa_off, units.pressureKpa) { onUnits(units.copy(pressureKpa = it)) }
        }
    }
}

@Composable
private fun UnitSwitch(@StringRes title: Int, @StringRes hint: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(hint)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(role = Role.Switch) { onChange(!checked) },
    )
}

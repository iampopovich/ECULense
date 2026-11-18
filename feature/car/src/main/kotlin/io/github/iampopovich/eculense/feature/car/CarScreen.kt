package io.github.iampopovich.eculense.feature.car

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.UnitsSettings
import io.github.iampopovich.eculense.core.navigation.CarEditDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import org.koin.compose.viewmodel.koinViewModel
import java.text.DateFormat
import java.util.Date

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
        item { Text("Автомобиль", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp)) }
        if (state.loading) return@LazyColumn
        state.active?.let { car -> item { ActiveCarCard(car, onEdit = { onEdit(car.id) }) } }
        item { SectionTitle("Машины") }
        items(state.cars, key = { it.id }) { car ->
            CarRow(car, selected = car.id == state.active?.id, onSelect = { onSelect(car.id) }, onEdit = { onEdit(car.id) })
        }
        item { OutlinedButton(onClick = onAdd) { Text("Добавить машину") } }
        item { SectionTitle("Единицы измерения") }
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
            Text(car.name, style = MaterialTheme.typography.titleLarge)
            CarFormat.title(car).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            CarFormat.engine(car).takeIf { it.isNotBlank() }?.let { Secondary(it) }
            car.fuelPrice?.let { Secondary("Цена топлива: ${CarFormat.number(it, 2)} ${car.currency.orEmpty()}/л") }
            car.tankVolumeL?.let { Secondary("Бак: ${CarFormat.number(it, 0)} л") }
            car.odometerKm?.let { km ->
                val date = car.odometerAtMs?.let { " на " + DateFormat.getDateInstance().format(Date(it)) }.orEmpty()
                Secondary("Пробег: ${CarFormat.number(km.toDouble(), 0)} км$date")
            }
            car.diagProfile?.let { Secondary("Профиль Car Scanner: $it") }
            if (car.brand == null && car.model == null && car.year == null) {
                Secondary("Заполните марку, модель и двигатель — это поможет ИИ и напоминаниям о ТО.")
            }
            FilledTonalButton(onClick = onEdit, modifier = Modifier.padding(top = 8.dp)) { Text("Изменить") }
        }
    }
}

@Composable
private fun Secondary(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CarRow(car: CarProfile, selected: Boolean, onSelect: () -> Unit, onEdit: () -> Unit) {
    ListItem(
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(car.name) },
        supportingContent = { Text(if (selected) "Активная — к ней привязываются новые записи" else CarFormat.title(car).ifBlank { "Нажмите, чтобы выбрать" }) },
        trailingContent = {
            IconButton(onClick = onEdit) { Icon(painterResource(R.drawable.ic_edit), contentDescription = "Изменить ${car.name}") }
        },
        modifier = Modifier.selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    )
}

@Composable
private fun UnitsCard(units: UnitsSettings, onUnits: (UnitsSettings) -> Unit) {
    Card {
        Column(Modifier.padding(vertical = 4.dp)) {
            UnitSwitch("Километры", "Выключено — мили", units.metricDistance) { onUnits(units.copy(metricDistance = it)) }
            UnitSwitch("Градусы Цельсия", "Выключено — Фаренгейт", units.celsius) { onUnits(units.copy(celsius = it)) }
            UnitSwitch("Расход в л/100 км", "Выключено — миль на галлон", units.litersPer100Km) { onUnits(units.copy(litersPer100Km = it)) }
            UnitSwitch("Объём в литрах", "Выключено — галлоны", units.liters) { onUnits(units.copy(liters = it)) }
            UnitSwitch("Давление в кПа", "Выключено — psi", units.pressureKpa) { onUnits(units.copy(pressureKpa = it)) }
        }
    }
}

@Composable
private fun UnitSwitch(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(hint) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(role = Role.Switch) { onChange(!checked) },
    )
}

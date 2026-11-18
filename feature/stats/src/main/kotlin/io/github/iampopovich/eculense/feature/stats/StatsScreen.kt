package io.github.iampopovich.eculense.feature.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.model.FuelMethod
import io.github.iampopovich.eculense.core.model.StatsPeriod
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import io.github.iampopovich.eculense.core.model.TripStats
import io.github.iampopovich.eculense.core.model.UnitsSettings
import io.github.iampopovich.eculense.feature.stats.StatsFormat.label
import org.koin.compose.viewmodel.koinViewModel
import io.github.iampopovich.eculense.core.ui.EcuDates

@Composable
internal fun StatsRoute(viewModel: StatsViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StatsScreen(state, onPeriod = viewModel::selectPeriod)
}

@Composable
internal fun StatsScreen(state: StatsUiState, onPeriod: (StatsPeriod) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text("Статистика", style = MaterialTheme.typography.headlineSmall)
                state.car?.let { Secondary(it.name) }
            }
        }
        if (state.loading) return@LazyColumn
        item { PeriodSelector(state.period, onPeriod) }
        if (state.totalTrips == 0) {
            item {
                Secondary(
                    "Поездок пока нет. Запишите поездку с адаптера (Панель → «Записывать») — после остановки записи " +
                        "поездка появится здесь. Или перенесите их из Car Scanner: Пит-стоп → Поездки → «Импорт из Car Scanner».",
                )
            }
            return@LazyColumn
        }
        if (state.trips.isEmpty()) {
            item { Secondary("За этот период поездок нет — выберите период длиннее.") }
            return@LazyColumn
        }
        item { Summary(state.stats, state.units, state.car?.currency) }
        item { Text("Поездки", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        items(state.trips, key = { it.id.takeIf { id -> id != 0L } ?: it.startedAtMs }) { TripRow(it, state.units) }
        item { Text(" ", Modifier.padding(bottom = 8.dp)) }
    }
}

@Composable
private fun PeriodSelector(selected: StatsPeriod, onPeriod: (StatsPeriod) -> Unit) {
    val periods = StatsFormat.periods
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        periods.forEachIndexed { i, p ->
            SegmentedButton(
                selected = selected == p,
                onClick = { onPeriod(p) },
                shape = SegmentedButtonDefaults.itemShape(i, periods.size),
                // без галочки: на узком экране она съедает подпись «Всё время»
                icon = {},
                label = { Text(p.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

@Composable
private fun Summary(s: TripStats, u: UnitsSettings, currency: String?) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatRow("Поездок", s.trips.toString(), "Пробег", StatsFormat.distance(s.distanceKm, u))
            StatRow("Топливо", StatsFormat.volume(s.fuelL, u), "Средний расход", StatsFormat.consumption(s.avgLPer100Km, u))
            StatRow("В движении", StatsFormat.duration(s.drivingMs), "На месте", StatsFormat.duration(s.standingMs))
            if (s.fuelCost > 0) StatRow("Затраты на топливо", StatsFormat.money(s.fuelCost, currency), null, null)
        }
    }
}

@Composable
private fun StatRow(label1: String, value1: String, label2: String?, value2: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat(label1, value1, Modifier.weight(1f))
        if (label2 != null && value2 != null) Stat(label2, value2, Modifier.weight(1f)) else Column(Modifier.weight(1f)) {}
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun TripRow(t: Trip, u: UnitsSettings) {
    val stats = TripStats.of(listOf(t))
    val approx = if (t.fuelMethod == FuelMethod.MAF_ESTIMATE) "≈ " else ""
    val fuel = if (!t.fuelKnown) "топливо: оценка недоступна"
    else "$approx${StatsFormat.volume(t.fuelL, u)} · $approx${StatsFormat.consumption(stats.avgLPer100Km, u)}"
    Column {
        ListItem(
            overlineContent = { Text(if (t.source == TripSource.IMPORT_CARSCANNER) "Car Scanner" else "Запись ECULense") },
            headlineContent = { Text(EcuDates.dateTime(t.startedAtMs)) },
            supportingContent = { Text("${StatsFormat.distance(t.distanceKm, u)} · $fuel") },
            trailingContent = { Text(StatsFormat.duration(t.drivingMs + t.standingMs), style = MaterialTheme.typography.labelMedium) },
        )
        HorizontalDivider()
    }
}

@Composable
private fun Secondary(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

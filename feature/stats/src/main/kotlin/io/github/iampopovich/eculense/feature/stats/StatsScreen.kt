package io.github.iampopovich.eculense.feature.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.model.FuelMethod
import io.github.iampopovich.eculense.core.model.StatsPeriod
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import io.github.iampopovich.eculense.core.model.TripStats
import io.github.iampopovich.eculense.core.model.UnitsSettings
import io.github.iampopovich.eculense.core.navigation.BackupDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.navigation.RouteHandler
import io.github.iampopovich.eculense.core.ui.CarNames
import io.github.iampopovich.eculense.core.ui.EcuDates
import io.github.iampopovich.eculense.feature.stats.StatsFormat.labelRes
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun StatsRoute(viewModel: StatsViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val koin = getKoin()
    // Car Scanner statistics import lives in :feature:backup; no feature, no link
    val hasBackup = remember { koin.getAll<RouteHandler>().any { it.routeClass == BackupDestination::class } }
    StatsScreen(state, onPeriod = viewModel::selectPeriod, onImport = if (hasBackup) ({ navigator.navigate(BackupDestination) }) else null)
}

@Composable
internal fun StatsScreen(state: StatsUiState, onPeriod: (StatsPeriod) -> Unit, modifier: Modifier = Modifier, onImport: (() -> Unit)? = null) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header(state.car?.let { CarNames.display(it) }, onImport)
        if (state.loading) return@Column
        PeriodSelector(state.period, onPeriod)
        if (state.trips.isEmpty()) {
            EmptyPlaceholder(stringResource(StatsFormat.emptyTitle(state.period, state.totalTrips)), Modifier.weight(1f))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Summary(state.stats, state.units, state.car?.currency) }
            item { Text(stringResource(R.string.stats_trips), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(state.trips, key = { it.id.takeIf { id -> id != 0L } ?: it.startedAtMs }) { TripRow(it, state.units) }
            item { Text(" ", Modifier.padding(bottom = 8.dp)) }
        }
    }
}

/**
 * Header: title + ONE import button for the whole screen; backup import does not depend on the selected period
 * (repeating the link in every branch read as "import for the week/month"). No :feature:backup, no button.
 */
@Composable
private fun Header(carName: String?, onImport: (() -> Unit)?) {
    // The button goes on its own row: on the same row as the title, with a large font «Статистика» wrapped letter by letter.
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(stringResource(R.string.stats_tab), style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            carName?.let { Secondary(it) }
        }
        onImport?.let {
            FilledTonalButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(painterResource(R.drawable.ic_import), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.stats_import), maxLines = 1)
            }
        }
    }
}

@Composable
private fun EmptyPlaceholder(title: String, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(bottom = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painterResource(R.drawable.ic_no_trips), contentDescription = null, modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.stats_empty_hint), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                // no checkmark: on a narrow screen it eats the «Всё время» label
                icon = {},
                // with a large font «Всё время» did not fit and was cut to «Всё»; shrink it rather than clip
                label = {
                    Text(
                        stringResource(p.labelRes),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize),
                    )
                },
            )
        }
    }
}

@Composable
private fun Summary(s: TripStats, u: UnitsSettings, currency: String?) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatRow(stringResource(R.string.stats_trip_count), s.trips.toString(), stringResource(R.string.stats_distance), StatsFormat.distance(s.distanceKm, u))
            StatRow(stringResource(R.string.stats_fuel), StatsFormat.volume(s.fuelL, u), stringResource(R.string.stats_avg_consumption), StatsFormat.consumption(s.avgLPer100Km, u))
            StatRow(stringResource(R.string.stats_driving), StatsFormat.duration(s.drivingMs), stringResource(R.string.stats_standing), StatsFormat.duration(s.standingMs))
            if (s.fuelCost > 0) StatRow(stringResource(R.string.stats_fuel_cost), StatsFormat.money(s.fuelCost, currency), null, null)
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
    val fuel = if (!t.fuelKnown) stringResource(R.string.stats_fuel_unknown)
    else "$approx${StatsFormat.volume(t.fuelL, u)} · $approx${StatsFormat.consumption(stats.avgLPer100Km, u)}"
    Column {
        ListItem(
            overlineContent = { Text(if (t.source == TripSource.IMPORT_CARSCANNER) "Car Scanner" else stringResource(R.string.stats_source_ecu)) },
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

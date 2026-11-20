package io.github.iampopovich.eculense.feature.fuel

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.analytics.CostSummary
import io.github.iampopovich.eculense.core.analyticstext.AnalyticsText
import io.github.iampopovich.eculense.core.model.Expense
import io.github.iampopovich.eculense.core.model.ExpenseCategory
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import io.github.iampopovich.eculense.core.model.StatsPeriod
import io.github.iampopovich.eculense.core.ui.CarNames
import org.koin.compose.viewmodel.koinViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import io.github.iampopovich.eculense.core.ui.R as CoreUiR

@StringRes
internal fun categoryRes(c: ExpenseCategory): Int = when (c) {
    ExpenseCategory.SERVICE -> R.string.expense_cat_service
    ExpenseCategory.REPAIR -> R.string.expense_cat_repair
    ExpenseCategory.PARTS -> R.string.expense_cat_parts
    ExpenseCategory.INSURANCE -> R.string.expense_cat_insurance
    ExpenseCategory.TAX -> R.string.expense_cat_tax
    ExpenseCategory.PARKING -> R.string.expense_cat_parking
    ExpenseCategory.WASH -> R.string.expense_cat_wash
    ExpenseCategory.OTHER -> R.string.expense_cat_other
}

@StringRes
private fun periodRes(p: StatsPeriod): Int = when (p) {
    StatsPeriod.WEEK -> R.string.expense_period_week
    StatsPeriod.MONTH -> R.string.expense_period_month
    StatsPeriod.YEAR -> R.string.expense_period_year
    StatsPeriod.ALL -> R.string.expense_period_all
}

@Composable
private fun workTitle(item: MaintenanceItem): String = when {
    item.title.isNotBlank() -> item.title
    item.kind != null -> stringResource(AnalyticsText.maintenanceKindRes(item.kind!!))
    else -> ""
}

@Composable
internal fun ExpensesRoute(viewModel: ExpensesViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ExpensesScreen(state, ZoneId.systemDefault(), viewModel::selectPeriod, viewModel::newForm, viewModel::formOf, viewModel::save, viewModel::delete)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExpensesScreen(
    state: ExpensesUiState,
    zone: ZoneId,
    onPeriod: (StatsPeriod) -> Unit,
    onNew: () -> ExpenseForm,
    onEdit: (Expense) -> ExpenseForm,
    onSave: (ExpenseForm) -> Boolean,
    onDelete: (Long) -> Unit,
) {
    var form by remember { mutableStateOf<ExpenseForm?>(null) }
    val works = state.works.map { it.item }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.expense_title), style = MaterialTheme.typography.headlineSmall)
                state.car?.let { Text(CarNames.display(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        item {
            FilledTonalButton(onClick = { form = onNew() }, enabled = state.car != null, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.expense_add))
            }
        }
        item { Periods(state.period, onPeriod) }
        state.summary?.let { s -> item { SummaryCard(s, state.car?.currency) } }
        if (!state.loading && state.expenses.isEmpty()) {
            item { Text(stringResource(R.string.expense_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 16.dp)) }
        }
        items(state.expenses, key = { it.id }) { e ->
            val linked = e.maintenanceItemId?.let { id -> works.firstOrNull { it.id == id } }
            Column {
                ListItem(
                    overlineContent = { Text(stringResource(categoryRes(e.category))) },
                    headlineContent = { Text("${FuelFormat.date(e.atMs, zone)} · ${FuelFormat.money(e.amount, state.car?.currency)}") },
                    supportingContent = {
                        val parts = listOfNotNull(
                            linked?.let { stringResource(R.string.expense_linked, workTitle(it)) },
                            e.odometerKm?.let { stringResource(R.string.fuel_km, FuelFormat.number(it.toDouble(), 0)) },
                            e.note,
                        )
                        if (parts.isNotEmpty()) Text(parts.joinToString(" · "))
                    },
                    modifier = Modifier.clickable { form = onEdit(e) },
                )
                HorizontalDivider()
            }
        }
        item { Text(" ", Modifier.padding(bottom = 16.dp)) }
    }
    form?.let { f ->
        ExpenseDialog(
            f, zone, state.nowMs, works,
            onSave = { if (onSave(it)) form = null },
            onDelete = if (f.id != 0L) ({ onDelete(f.id); form = null }) else null,
            onDismiss = { form = null },
        )
    }
}

@Composable
private fun Periods(selected: StatsPeriod, onPeriod: (StatsPeriod) -> Unit) {
    val periods = listOf(StatsPeriod.WEEK, StatsPeriod.MONTH, StatsPeriod.YEAR, StatsPeriod.ALL)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        periods.forEachIndexed { i, p ->
            SegmentedButton(
                selected = selected == p, onClick = { onPeriod(p) }, shape = SegmentedButtonDefaults.itemShape(i, periods.size), icon = {},
                label = {
                    Text(
                        stringResource(periodRes(p)), maxLines = 1, style = MaterialTheme.typography.labelMedium,
                        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize),
                    )
                },
            )
        }
    }
}

@Composable
private fun SummaryCard(s: CostSummary, currency: String?) {
    val month = remember { DateTimeFormatter.ofPattern("LLLL yyyy") }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.expense_total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(FuelFormat.money(s.total, currency), style = MaterialTheme.typography.headlineSmall)
            s.perMonth?.let { Line(R.string.expense_per_month, FuelFormat.money(it, currency)) }
            s.perDay?.let { Line(R.string.expense_per_day, FuelFormat.money(it, currency)) }
            Line(R.string.expense_per_km, s.perKm?.let { FuelFormat.money(it, currency) } ?: stringResource(R.string.expense_per_km_unknown))
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (s.fuel > 0) Line(R.string.expense_cat_fuel, FuelFormat.money(s.fuel, currency))
            s.byCategory.entries.sortedByDescending { it.value }.forEach { (c, v) -> Line(categoryRes(c), FuelFormat.money(v, currency)) }
            if (s.byMonth.size > 1) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                s.byMonth.take(6).forEach { (m, v) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(month.format(m), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(FuelFormat.money(v, currency), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Line(@StringRes label: Int, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpenseDialog(
    initial: ExpenseForm,
    zone: ZoneId,
    nowMs: Long,
    works: List<MaintenanceItem>,
    onSave: (ExpenseForm) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var f by remember { mutableStateOf(initial) }
    var menu by remember { mutableStateOf(false) }
    val errors = f.errors(zone, nowMs)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.id == 0L) R.string.expense_add else R.string.expense_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExpenseCategory.entries.forEach { c ->
                        FilterChip(selected = f.category == c, onClick = { f = f.copy(category = c) }, label = { Text(stringResource(categoryRes(c))) })
                    }
                }
                // A blank amount blocks Save but isn't shown as an error before anything was typed.
                Field(f.amount, R.string.expense_field_amount, ExpenseError.AMOUNT in errors && f.amount.isNotBlank(), R.string.fuel_error_number, KeyboardType.Decimal, "exp-amount") { f = f.copy(amount = it) }
                Field(f.date, R.string.fuel_field_date, ExpenseError.DATE in errors, R.string.fuel_error_date, KeyboardType.Text, "exp-date") { f = f.copy(date = it) }
                Field(f.odometer, R.string.fuel_field_odometer, ExpenseError.ODOMETER in errors, R.string.fuel_error_odometer, KeyboardType.Number, "exp-odo",
                    hint = R.string.expense_odometer_hint) { f = f.copy(odometer = it) }
                Field(f.note, R.string.expense_field_note, false, R.string.fuel_error_number, KeyboardType.Text, "exp-note") { f = f.copy(note = it) }
                if (f.canLink && works.isNotEmpty()) {
                    Text(stringResource(R.string.expense_link_title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    Box {
                        OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(f.maintenanceItemId?.let { id -> works.firstOrNull { it.id == id }?.let { workTitle(it) } } ?: stringResource(R.string.expense_link_none))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.expense_link_none)) }, onClick = { f = f.copy(maintenanceItemId = null); menu = false })
                            works.forEach { w ->
                                DropdownMenuItem(text = { Text(workTitle(w)) }, onClick = { f = f.copy(maintenanceItemId = w.id); menu = false })
                            }
                        }
                    }
                    if (f.maintenanceItemId != null) {
                        Text(stringResource(R.string.expense_link_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                onDelete?.let { TextButton(onClick = it) { Text(stringResource(CoreUiR.string.common_delete), color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(f) }, enabled = errors.isEmpty()) { Text(stringResource(CoreUiR.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiR.string.common_cancel)) } },
    )
}


package io.github.iampopovich.eculense.feature.car

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.analytics.EngineTraits
import io.github.iampopovich.eculense.core.analytics.FuelKind
import io.github.iampopovich.eculense.core.analytics.NormKey
import io.github.iampopovich.eculense.core.analytics.NormSource
import io.github.iampopovich.eculense.core.analytics.NormText
import io.github.iampopovich.eculense.core.analytics.O2Kind
import io.github.iampopovich.eculense.core.analytics.Trait
import io.github.iampopovich.eculense.core.analyticstext.AnalyticsText
import io.github.iampopovich.eculense.core.data.NormRow
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun CarNormsRoute(viewModel: CarNormsViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CarNormsScreen(state, onSave = viewModel::save, onReset = viewModel::reset)
}

/** R-H: compact rows per norm (value, source chip, sample count), bottom sheet to edit or reset; traits on top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CarNormsScreen(state: CarNormsUiState, onSave: (NormKey, String, String) -> NormInputError?, onReset: (NormKey) -> Unit) {
    var open by remember { mutableStateOf<NormKey?>(null) }
    val view = state.view
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.car_norms_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp)) }
        if (state.loading) return@LazyColumn
        if (view == null) {
            item { Text(stringResource(R.string.car_norms_no_car)) }
            return@LazyColumn
        }
        item { Text(stringResource(R.string.car_norms_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { TraitsCard(view.norms.traits) }
        val learned = view.rows.mapNotNull { it.baseline?.sampleCount }.maxOrNull() ?: 0
        item {
            Text(
                if (learned > 0) pluralStringResource(R.plurals.car_norms_learned, learned, learned) else stringResource(R.string.car_norms_not_learned),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        items(view.rows, key = { it.key.name }) { row -> NormListRow(row) { open = row.key } }
        item { Text(stringResource(R.string.car_norms_maintenance_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp)) }
    }
    val row = open?.let { k -> view?.rows?.firstOrNull { it.key == k } }
    if (row != null) {
        ModalBottomSheet(onDismissRequest = { open = null }) {
            NormSheet(row, onSave = { lo, hi -> onSave(row.key, lo, hi).also { if (it == null) open = null } }, onReset = { onReset(row.key); open = null })
        }
    }
}

@Composable
private fun TraitsCard(t: EngineTraits) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.car_norms_traits), style = MaterialTheme.typography.titleMedium)
            TraitLine(R.string.car_norms_trait_fuel, t.fuel) { stringResource(fuelRes(it)) }
            TraitLine(R.string.car_norms_trait_o2, t.o2) { stringResource(if (it == O2Kind.WIDEBAND) R.string.car_norms_o2_wide else R.string.car_norms_o2_narrow) }
            TraitLine(R.string.car_norms_trait_boost, t.forcedInduction) { stringResource(if (it) R.string.car_norms_yes else R.string.car_norms_unknown_no) }
            TraitLine(R.string.car_norms_trait_maf, t.hasMaf) { stringResource(if (it) R.string.car_norms_yes else R.string.car_norms_no) }
            TraitLine(R.string.car_norms_trait_obd, t.obdStandard) { it.toString() }
        }
    }
}

@Composable
private fun <T> TraitLine(label: Int, trait: Trait<T>?, value: @Composable (T) -> String) {
    val text = if (trait == null) stringResource(R.string.car_norms_trait_unknown)
    else stringResource(R.string.car_norms_trait_value, value(trait.value), stringResource(sourceRes(trait.source)))
    Text(stringResource(R.string.car_norms_trait_line, stringResource(label), text), style = MaterialTheme.typography.bodyMedium)
}

private fun fuelRes(f: FuelKind) = when (f) {
    FuelKind.PETROL -> R.string.car_norms_fuel_petrol
    FuelKind.DIESEL -> R.string.car_norms_fuel_diesel
    FuelKind.GAS -> R.string.car_norms_fuel_gas
    FuelKind.ELECTRIC -> R.string.car_norms_fuel_electric
    FuelKind.HYBRID -> R.string.car_norms_fuel_hybrid
    FuelKind.OTHER -> R.string.car_norms_fuel_other
}

/** Short chip label of a source. */
private fun sourceRes(s: NormSource) = when (s) {
    NormSource.USER -> R.string.car_norms_src_user
    NormSource.REFERENCE -> R.string.car_norms_src_reference
    NormSource.BASELINE -> R.string.car_norms_src_baseline
    NormSource.ECU -> R.string.car_norms_src_ecu
    NormSource.GENERIC -> R.string.car_norms_src_generic
}

@Composable
private fun NormListRow(row: NormRow, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val n = row.effective
    ListItem(
        headlineContent = { Text(AnalyticsText.render(ctx, NormText.name(row.key))) },
        supportingContent = {
            Column {
                Text(n?.let { AnalyticsText.render(ctx, NormText.range(it)) } ?: stringResource(R.string.car_norms_none))
                details(row)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        trailingContent = { n?.let { SuggestionChip(onClick = onClick, label = { Text(stringResource(sourceRes(it.source))) }) } },
        modifier = Modifier.heightIn(min = 48.dp).clickable(onClick = onClick),
    )
}

/** «Learned: recordings 12, confidence 40 %» or «learning: 3 of 5». */
@Composable
private fun details(row: NormRow): String? {
    val b = row.baseline ?: return null
    return if (b.sampleCount < io.github.iampopovich.eculense.core.analytics.BaselineLearner.MIN_SESSIONS) {
        stringResource(R.string.car_norms_learning, b.sampleCount, io.github.iampopovich.eculense.core.analytics.BaselineLearner.MIN_SESSIONS)
    } else {
        stringResource(R.string.car_norms_learned_line, b.sampleCount, Math.round(b.confidence * 100).toInt())
    }
}

@Composable
private fun NormSheet(row: NormRow, onSave: (String, String) -> NormInputError?, onReset: () -> Unit) {
    val ctx = LocalContext.current
    val current = row.user ?: row.effective
    var low by rememberSaveable(row.key) { mutableStateOf(current?.low?.let(::plain).orEmpty()) }
    var high by rememberSaveable(row.key) { mutableStateOf(current?.high?.let(::plain).orEmpty()) }
    var error by remember(row.key) { mutableStateOf<NormInputError?>(null) }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(AnalyticsText.render(ctx, NormText.name(row.key)), style = MaterialTheme.typography.titleLarge)
        row.effective?.let { Text(stringResource(R.string.car_norms_in_use, AnalyticsText.render(ctx, NormText.comparedWith(it)))) }
        row.baseline?.takeIf { it !== row.effective }?.let { Text(stringResource(R.string.car_norms_learned_value, AnalyticsText.render(ctx, NormText.comparedWith(it))), style = MaterialTheme.typography.bodyMedium) }
        if (!CarNormsViewModel.editable(row.key)) {
            Text(stringResource(R.string.car_norms_harsh_hint), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text(stringResource(R.string.car_norms_edit_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        OutlinedTextField(low, { low = it; error = null }, label = { Text(stringResource(R.string.car_norms_low)) }, singleLine = true, keyboardOptions = keyboard, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(high, { high = it; error = null }, label = { Text(stringResource(R.string.car_norms_high)) }, singleLine = true, keyboardOptions = keyboard, modifier = Modifier.fillMaxWidth())
        error?.let {
            val res = when (it) {
                NormInputError.NOT_A_NUMBER -> R.string.car_norms_err_number
                NormInputError.EMPTY -> R.string.car_norms_err_empty
                NormInputError.OUT_OF_RANGE -> R.string.car_norms_err_range
                NormInputError.LOW_ABOVE_HIGH -> R.string.car_norms_err_order
            }
            Text(stringResource(res), color = MaterialTheme.colorScheme.error)
        }
        FilledTonalButton(onClick = { error = onSave(low, high) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.car_norms_save)) }
        if (row.user != null) {
            val target = if ((row.baseline?.sampleCount ?: 0) >= io.github.iampopovich.eculense.core.analytics.BaselineLearner.MIN_SESSIONS) R.string.car_norms_reset_learned else R.string.car_norms_reset_generic
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(target)) }
        }
    }
}

private fun plain(v: Double): String = if (v == Math.rint(v)) v.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')

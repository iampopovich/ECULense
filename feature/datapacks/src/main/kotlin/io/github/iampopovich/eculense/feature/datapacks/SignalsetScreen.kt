package io.github.iampopovich.eculense.feature.datapacks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.obdb.ProfileField
import io.github.iampopovich.eculense.core.obdb.SignalsetResult
import io.github.iampopovich.eculense.core.ui.YearRange
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun SignalsetRoute(viewModel: SignalsetViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SignalsetScreen(state, onYear = viewModel::setYear, onCheck = viewModel::check)
}

/**
 * OBDb signalset for the active car: view only. The year can be changed to view a covered generation
 * (format check); polling extended PIDs is backlog 1.24 items 6–7.
 */
@Composable
internal fun SignalsetScreen(state: SignalsetUiState, onYear: (String) -> Unit, onCheck: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.datapacks_car, state.carTitle?.asString() ?: stringResource(R.string.datapacks_car_none)), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.datapacks_signalset_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.year, onValueChange = onYear, label = { Text(stringResource(R.string.datapacks_year)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(120.dp),
                    )
                    Button(onClick = onCheck, enabled = !state.loading) { Text(stringResource(R.string.datapacks_check_obdb)) }
                }
                if (state.loading) LinearProgressIndicator()
                state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
            }
        }
        when (val r = state.result) {
            null -> Unit
            is SignalsetResult.NeedsProfile -> item { Text(stringResource(R.string.datapacks_needs_profile, r.missing.map { stringResource(it.labelRes()) }.joinToString())) }
            is SignalsetResult.NoRepository -> item { Text(stringResource(R.string.datapacks_no_repo, r.repo)) }
            is SignalsetResult.NotCovered -> item {
                val gen = r.generation
                Text(
                    if (gen != null) stringResource(R.string.datapacks_not_covered_gen, r.repo, r.year, gen.name, YearRange.of(gen.startYear, gen.endYear)!!.asString())
                    else stringResource(R.string.datapacks_not_covered, r.repo, r.year),
                )
            }
            is SignalsetResult.Covered -> {
                item {
                    val gen = r.generation
                    Text(
                        if (gen != null) stringResource(R.string.datapacks_covered_gen, r.repo, r.year, r.commands.size, r.signalCount, r.ecus.size, r.version, gen.name)
                        else stringResource(R.string.datapacks_covered, r.repo, r.year, r.commands.size, r.signalCount, r.ecus.size, r.version),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                r.commands.forEachIndexed { i, cmd ->
                    item(key = "cmd-$i") {
                        Text(cmd.label + (r.ecus.firstOrNull { it.hdr == cmd.hdr }?.let { " · ${it.type}" } ?: ""), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(cmd.signals, key = { "sig-$i-" + it.id }) { s ->
                        ListItem(
                            headlineContent = { Text(s.name) },
                            supportingContent = { Text(listOfNotNull(s.id, s.path, s.fmt?.unit).joinToString(" · ")) },
                        )
                    }
                }
            }
        }
    }
}

private fun ProfileField.labelRes(): Int = when (this) {
    ProfileField.MAKE -> R.string.datapacks_field_make
    ProfileField.MODEL -> R.string.datapacks_field_model
    ProfileField.YEAR -> R.string.datapacks_field_year
}

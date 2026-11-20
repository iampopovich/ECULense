package io.github.iampopovich.eculense.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.analytics.AlertThreshold
import io.github.iampopovich.eculense.core.analytics.LiveAlertConfig
import io.github.iampopovich.eculense.core.analytics.LiveAlertKind
import io.github.iampopovich.eculense.core.data.AlertPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import java.util.Locale

internal data class AlertRow(val kind: LiveAlertKind, val enabled: Boolean, val text: String, val valid: Boolean = true)

internal data class AlertSettingsUiState(
    val loaded: Boolean = false,
    val rows: List<AlertRow> = emptyList(),
    val newDtc: Boolean = true,
    /** true after the latest edit reached the store and every number is valid. */
    val saved: Boolean = false,
)

/** Parses «110», «12.0» or «12,0»; null when it is not a number inside [LiveAlertConfig.validRange]. */
internal fun parseThreshold(kind: LiveAlertKind, text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in LiveAlertConfig.validRange(kind) }

internal fun formatThreshold(kind: LiveAlertKind, v: Double): String =
    if (kind == LiveAlertKind.OVERHEAT) String.format(Locale.ROOT, "%.0f", v) else String.format(Locale.ROOT, "%.1f", v)

/**
 * C4 thresholds: switch each alert off or change its number; numbers are typed here, never on the dashboard.
 * Every change is stored on its own (no Save button): switches at once, typed numbers after [debounceMs] of quiet. A row
 * whose text is not a valid number keeps its last stored value until the text is fixed. Saves run in [saveScope] (the app
 * scope in production) so an edit made right before leaving the screen is not lost.
 */
internal class AlertSettingsViewModel(
    private val prefs: AlertPrefs,
    saveScope: CoroutineScope? = null,
    private val debounceMs: Long = DEBOUNCE_MS,
) : ViewModel() {
    private val scope = saveScope ?: viewModelScope
    private val _state = MutableStateFlow(AlertSettingsUiState())
    val state: StateFlow<AlertSettingsUiState> = _state.asStateFlow()
    private var pending: Job? = null

    init {
        viewModelScope.launch {
            val c = prefs.config.first()
            _state.value = AlertSettingsUiState(
                loaded = true,
                rows = LiveAlertKind.entries.map { AlertRow(it, c.of(it).enabled, formatThreshold(it, c.of(it).value)) },
                newDtc = c.newDtc,
            )
        }
    }

    fun setEnabled(kind: LiveAlertKind, on: Boolean) {
        edit(kind) { it.copy(enabled = on) }
        persist(0)
    }

    fun setText(kind: LiveAlertKind, text: String) {
        edit(kind) { it.copy(text = text, valid = parseThreshold(kind, text) != null) }
        persist(debounceMs)
    }

    fun setNewDtc(on: Boolean) {
        _state.update { it.copy(newDtc = on, saved = false) }
        persist(0)
    }

    /** Restores the shipped defaults (the batch acceptance pass lowers a threshold and puts it back with this). */
    fun resetDefaults() {
        val d = LiveAlertConfig()
        _state.update { s ->
            s.copy(rows = LiveAlertKind.entries.map { AlertRow(it, d.of(it).enabled, formatThreshold(it, d.of(it).value)) }, newDtc = d.newDtc, saved = false)
        }
        persist(0)
    }

    private fun persist(delayMs: Long) {
        if (!_state.value.loaded) return
        pending?.cancel()
        pending = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val s = _state.value
            prefs.update { c ->
                s.rows.fold(c.copy(newDtc = s.newDtc)) { acc, r ->
                    // An invalid number keeps the stored value; its switch is stored anyway.
                    val value = parseThreshold(r.kind, r.text) ?: acc.of(r.kind).value
                    acc.with(r.kind, AlertThreshold(r.enabled, value))
                }
            }
            if (_state.value == s) _state.update { it.copy(saved = s.rows.all { r -> r.valid }) }
        }
    }

    private fun edit(kind: LiveAlertKind, f: (AlertRow) -> AlertRow) =
        _state.update { s -> s.copy(rows = s.rows.map { if (it.kind == kind) f(it) else it }, saved = false) }

    companion object {
        const val DEBOUNCE_MS = 600L
    }
}

@Composable
internal fun AlertSettingsRoute(viewModel: AlertSettingsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.alerts_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.alerts_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.rows.forEach { row ->
            ListItem(
                headlineContent = { Text(stringResource(alertTitle(row.kind))) },
                supportingContent = { Text(stringResource(alertHint(row.kind))) },
                trailingContent = { Switch(checked = row.enabled, onCheckedChange = null) },
                modifier = Modifier.toggleable(row.enabled, role = Role.Switch) { viewModel.setEnabled(row.kind, it) },
            )
            val range = LiveAlertConfig.validRange(row.kind)
            OutlinedTextField(
                value = row.text,
                onValueChange = { viewModel.setText(row.kind, it) },
                enabled = row.enabled,
                singleLine = true,
                isError = !row.valid,
                label = { Text(stringResource(if (row.kind == LiveAlertKind.OVERHEAT) R.string.alerts_value_celsius else R.string.alerts_value_volts)) },
                supportingText = {
                    Text(stringResource(R.string.alerts_range, formatThreshold(row.kind, range.start), formatThreshold(row.kind, range.endInclusive)))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.alerts_new_dtc)) },
            supportingContent = { Text(stringResource(R.string.alerts_new_dtc_hint)) },
            trailingContent = { Switch(checked = state.newDtc, onCheckedChange = null) },
            modifier = Modifier.toggleable(state.newDtc, role = Role.Switch) { viewModel.setNewDtc(it) },
        )
        androidx.compose.material3.TextButton(onClick = viewModel::resetDefaults, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.alerts_defaults))
        }
        if (state.saved) Text(stringResource(R.string.alerts_saved), color = MaterialTheme.colorScheme.primary)
    }
}

internal fun alertTitle(kind: LiveAlertKind): Int = when (kind) {
    LiveAlertKind.OVERHEAT -> R.string.alerts_overheat
    LiveAlertKind.NOT_CHARGING -> R.string.alerts_not_charging
    LiveAlertKind.OVERCHARGE -> R.string.alerts_overcharge
}

private fun alertHint(kind: LiveAlertKind): Int = when (kind) {
    LiveAlertKind.OVERHEAT -> R.string.alerts_overheat_hint
    LiveAlertKind.NOT_CHARGING -> R.string.alerts_not_charging_hint
    LiveAlertKind.OVERCHARGE -> R.string.alerts_overcharge_hint
}

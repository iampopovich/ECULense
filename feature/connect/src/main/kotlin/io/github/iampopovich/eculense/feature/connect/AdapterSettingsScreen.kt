package io.github.iampopovich.eculense.feature.connect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import io.github.iampopovich.eculense.core.data.AutomationSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.obd.AdaptiveTiming
import io.github.iampopovich.eculense.core.obd.InitCommandProblem
import io.github.iampopovich.eculense.core.obd.ObdProtocols
import io.github.iampopovich.eculense.core.obdtext.ObdText
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun AdapterSettingsRoute(viewModel: AdapterSettingsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detected by viewModel.detectedProtocol.collectAsStateWithLifecycle()
    val automation by viewModel.automation.collectAsStateWithLifecycle()
    AdapterSettingsScreen(
        state, detected, viewModel::setProtocol, viewModel::setTiming, viewModel::setExtraInit, viewModel::save,
        automation = automation, onAutomation = viewModel::setAutomation,
    )
}

/** C5: switches apply at once; auto-connect needs the remembered adapter. */
@Composable
private fun AutomationGroup(settings: AutomationSettings, onChange: ((AutomationSettings) -> AutomationSettings) -> Unit) {
    Text(stringResource(R.string.automation_title), style = MaterialTheme.typography.titleMedium)
    SwitchRow(R.string.automation_remember, R.string.automation_remember_hint, settings.rememberLast) { on ->
        onChange { it.copy(rememberLast = on, autoConnect = it.autoConnect && on) }
    }
    SwitchRow(R.string.automation_connect, R.string.automation_connect_hint, settings.autoConnect, enabled = settings.rememberLast) { on ->
        onChange { it.copy(autoConnect = on) }
    }
    SwitchRow(R.string.automation_record, R.string.automation_record_hint, settings.autoRecord) { on -> onChange { it.copy(autoRecord = on) } }
    SwitchRow(R.string.automation_ask_car, R.string.automation_ask_car_hint, settings.askCar) { on -> onChange { it.copy(askCar = on) } }
    HorizontalDivider()
}

@Composable
private fun SwitchRow(title: Int, hint: Int, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(hint)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdapterSettingsScreen(
    state: AdapterSettingsUiState,
    detected: Char?,
    onProtocol: (Char?) -> Unit,
    onTiming: (AdaptiveTiming) -> Unit,
    onExtraInit: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    automation: AutomationSettings? = null,
    onAutomation: ((AutomationSettings) -> AutomationSettings) -> Unit = {},
) {
    var picking by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.adapter_settings_title), style = MaterialTheme.typography.titleLarge)
        automation?.let { AutomationGroup(it, onAutomation) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.adapter_settings_protocol)) },
            supportingContent = { Text(ObdText.protocolName(state.protocol).asString()) },
            modifier = Modifier.clickable { picking = true },
        )
        Text(
            if (detected != null) {
                stringResource(R.string.adapter_settings_detected, ObdText.protocolName(detected).asString())
            } else {
                stringResource(R.string.adapter_settings_detected_none)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.adapter_settings_timing), style = MaterialTheme.typography.titleSmall)
        TimingSelector(state.timing, onTiming)
        OutlinedTextField(
            value = state.extraInit,
            onValueChange = onExtraInit,
            label = { Text(stringResource(R.string.adapter_settings_extra_init)) },
            minLines = 3,
            isError = state.problems.isNotEmpty() || state.tooMany,
            supportingText = {
                val errors = state.problems.map { problemText(it) } +
                    if (state.tooMany) listOf(stringResource(R.string.adapter_settings_too_many)) else emptyList()
                Text(if (errors.isEmpty()) stringResource(R.string.adapter_settings_extra_init_hint) else errors.joinToString("\n"))
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onSave, enabled = state.loaded && state.problems.isEmpty() && !state.tooMany) {
            Text(stringResource(R.string.adapter_settings_save))
        }
        if (state.saved) {
            Text(stringResource(R.string.adapter_settings_saved), color = MaterialTheme.colorScheme.primary)
        }
    }
    if (picking) {
        ModalBottomSheet(onDismissRequest = { picking = false }) {
            LazyColumn {
                items(listOf<Char?>(null) + ObdProtocols.ALL.toList()) { p ->
                    ListItem(
                        leadingContent = { RadioButton(selected = state.protocol == p, onClick = null) },
                        headlineContent = { Text(ObdText.protocolName(p).asString()) },
                        modifier = Modifier.clickable {
                            onProtocol(p)
                            picking = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TimingSelector(selected: AdaptiveTiming, onTiming: (AdaptiveTiming) -> Unit) {
    val options = AdaptiveTiming.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, t ->
            SegmentedButton(
                selected = selected == t,
                onClick = { onTiming(t) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
                label = {
                    Text(
                        stringResource(
                            when (t) {
                                AdaptiveTiming.AUTO -> R.string.adapter_timing_auto
                                AdaptiveTiming.OFF -> R.string.adapter_timing_off
                                AdaptiveTiming.NORMAL -> R.string.adapter_timing_1
                                AdaptiveTiming.AGGRESSIVE -> R.string.adapter_timing_2
                            },
                        ),
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
private fun problemText(problem: InitCommandProblem): String = when (problem) {
    is InitCommandProblem.NotAtCommand -> stringResource(R.string.adapter_settings_not_at, problem.line)
    is InitCommandProblem.Forbidden -> stringResource(R.string.adapter_settings_forbidden, problem.line)
}

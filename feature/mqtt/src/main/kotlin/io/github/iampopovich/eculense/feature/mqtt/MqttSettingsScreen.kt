package io.github.iampopovich.eculense.feature.mqtt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.mqtt.ConnectResult
import io.github.iampopovich.eculense.core.mqtt.MqttState
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun MqttSettingsRoute(viewModel: MqttSettingsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MqttSettingsScreen(state, viewModel)
}

@Composable
internal fun MqttSettingsScreen(s: MqttUiState, vm: MqttSettingsViewModel) {
    if (!s.loaded) return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.mqtt_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        // On/off: locked until «Check connection» proved these settings (error prevention), always switchable off.
        SwitchRow(stringResource(R.string.mqtt_enabled), s.enabled, enabled = s.canEnable, onChange = vm::setEnabled)
        if (!s.canEnable) Hint(stringResource(R.string.mqtt_enable_locked))
        Text(statusText(s), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))

        Section(stringResource(R.string.mqtt_section_broker))
        Field(s.host, vm::setHost, stringResource(R.string.mqtt_host), KeyboardType.Uri, error = if (s.plainNotAllowed) stringResource(R.string.mqtt_plain_not_allowed) else null)
        Field(s.port, vm::setPort, stringResource(R.string.mqtt_port), KeyboardType.Number, error = if (s.portValue == null) stringResource(R.string.mqtt_port_invalid) else null)
        SwitchRow(stringResource(R.string.mqtt_tls), s.tls, onChange = vm::setTls)
        Field(s.username, vm::setUsername, stringResource(R.string.mqtt_username), KeyboardType.Text)
        Field(
            s.password, vm::setPassword,
            stringResource(if (s.hasPassword) R.string.mqtt_password_saved else R.string.mqtt_password),
            KeyboardType.Password, password = true,
        )
        if (s.hasPassword) {
            TextButton(onClick = vm::clearPassword, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.mqtt_password_clear)) }
        }
        Button(onClick = vm::check, enabled = s.canCheck, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.mqtt_check))
        }
        checkText(s.check)?.let { (text, ok) ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Section(stringResource(R.string.mqtt_section_data))
        Field(s.prefix, vm::setPrefix, stringResource(R.string.mqtt_prefix), KeyboardType.Uri)
        Field(s.period, vm::setPeriod, stringResource(R.string.mqtt_period), KeyboardType.Number, error = if (s.periodValue == null) stringResource(R.string.mqtt_period_invalid) else null)
        SwitchRow(stringResource(R.string.mqtt_send_dashboard), s.sendDashboard, onChange = vm::setSendDashboard)
        SwitchRow(stringResource(R.string.mqtt_send_battery), s.sendBattery, onChange = vm::setSendBattery)
        SwitchRow(stringResource(R.string.mqtt_send_connection), s.sendConnection, onChange = vm::setSendConnection)
        SwitchRow(stringResource(R.string.mqtt_send_dtc), s.sendDtcCount, onChange = vm::setSendDtcCount)

        HorizontalDivider(Modifier.padding(top = 8.dp))
        Hint(stringResource(R.string.mqtt_privacy))
        Hint(stringResource(R.string.mqtt_ha_hint))
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, type: KeyboardType, password: Boolean = false, error: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

@Composable
private fun statusText(s: MqttUiState): String = when (val b = s.bridge) {
    BridgeStatus.Off -> stringResource(R.string.mqtt_status_off)
    BridgeStatus.WaitingForAdapter -> stringResource(R.string.mqtt_status_waiting_adapter)
    BridgeStatus.SimulatedLink -> stringResource(R.string.mqtt_status_simulated)
    is BridgeStatus.Active -> when (val m = b.mqtt) {
        MqttState.Idle -> stringResource(R.string.mqtt_status_off)
        is MqttState.Connecting -> stringResource(R.string.mqtt_status_connecting)
        MqttState.Connected -> stringResource(R.string.mqtt_status_publishing)
        is MqttState.Waiting -> stringResource(R.string.mqtt_status_retry, (m.retryInMs / 1_000).coerceAtLeast(1).toInt())
        is MqttState.Failed -> stringResource(R.string.mqtt_status_failed, resultText(m.error))
    }
}

@Composable
private fun checkText(c: CheckState): Pair<String, Boolean>? = when (c) {
    CheckState.Idle -> null
    CheckState.Running -> stringResource(R.string.mqtt_checking) to true
    is CheckState.Done -> resultText(c.result) to (c.result == ConnectResult.Accepted)
}

@Composable
private fun resultText(r: ConnectResult): String = when {
    r == ConnectResult.Accepted -> stringResource(R.string.mqtt_result_ok)
    r.badCredentials -> stringResource(R.string.mqtt_result_bad_credentials)
    r is ConnectResult.Refused -> stringResource(R.string.mqtt_result_refused, r.code.code)
    r is ConnectResult.Unreachable -> stringResource(R.string.mqtt_result_unreachable)
    r is ConnectResult.TlsFailed -> stringResource(R.string.mqtt_result_tls)
    r is ConnectResult.Protocol -> stringResource(R.string.mqtt_result_protocol)
    else -> stringResource(R.string.mqtt_plain_not_allowed)
}

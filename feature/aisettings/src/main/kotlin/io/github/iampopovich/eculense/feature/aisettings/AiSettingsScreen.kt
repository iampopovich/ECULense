package io.github.iampopovich.eculense.feature.aisettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.ai.LlmProviderKind
import io.github.iampopovich.eculense.core.ai.ModelInfo
import io.github.iampopovich.eculense.core.navigation.LocalModelDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.ui.R as CoreUiR
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** Screen actions; empty by default — handy in UI tests. */
internal class AiSettingsActions(
    val onProvider: (LlmProviderKind) -> Unit = {},
    val onBaseUrl: (String) -> Unit = {},
    val onApiKey: (String) -> Unit = {},
    val onCheck: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onModel: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onEdit: () -> Unit = {},
    val onCancelEdit: () -> Unit = {},
    val onClearKey: () -> Unit = {},
    val onReset: () -> Unit = {},
    /** The «На устройстве» item — open the on-device model screen. */
    val onOnDevice: () -> Unit = {},
    /** Stop using the on-device model. */
    val onUseCloud: () -> Unit = {},
    /** Open the cloud provider wizard (when only the on-device model is saved). */
    val onStartWizard: () -> Unit = {},
)

@Composable
internal fun AiSettingsRoute(viewModel: AiSettingsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    AiSettingsScreen(
        state,
        AiSettingsActions(
            onProvider = viewModel::selectProvider,
            onBaseUrl = viewModel::setBaseUrl,
            onApiKey = viewModel::setApiKey,
            onCheck = viewModel::check,
            onQuery = viewModel::setQuery,
            onModel = viewModel::selectModel,
            onSave = viewModel::save,
            onEdit = viewModel::edit,
            onCancelEdit = viewModel::cancelEdit,
            onClearKey = viewModel::clearKey,
            onReset = viewModel::reset,
            onOnDevice = { navigator.navigate(LocalModelDestination) },
            onUseCloud = viewModel::useCloud,
            onStartWizard = viewModel::startWizard,
        ),
    )
}

@Composable
internal fun AiSettingsScreen(state: AiSettingsUiState, actions: AiSettingsActions, modifier: Modifier = Modifier) {
    if (!state.loaded) return
    val saved = state.saved
    if (!state.editing && (saved != null || state.onDevice != null)) {
        SavedSummary(state, actions, modifier)
    } else {
        Wizard(state, actions, modifier)
    }
}

@Composable
private fun SavedSummary(state: AiSettingsUiState, actions: AiSettingsActions, modifier: Modifier) {
    val saved = state.saved
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.aisettings_hub_title), style = MaterialTheme.typography.headlineSmall)
        state.onDevice?.let { local ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.aisettings_current_on_device, local.model), style = MaterialTheme.typography.titleMedium)
                    Text(local.detail, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.aisettings_on_device_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onOnDevice) { Text(stringResource(R.string.aisettings_phone_model)) }
                OutlinedButton(onClick = actions.onUseCloud) { Text(stringResource(if (saved != null) R.string.aisettings_switch_to_cloud else R.string.aisettings_dont_use)) }
            }
        }
        if (saved == null) {
            if (state.onDevice != null) OutlinedButton(onClick = actions.onStartWizard) { Text(stringResource(R.string.aisettings_setup_cloud)) }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val key = stringResource(
                        when {
                            !saved.provider.usesKey -> R.string.aisettings_key_none
                            saved.hasKey -> R.string.aisettings_key_saved
                            saved.provider.needsKey -> R.string.aisettings_key_missing
                            else -> R.string.aisettings_key_none
                        },
                    )
                    if (state.onDevice != null) {
                        Text(stringResource(R.string.aisettings_cloud_inactive), style = MaterialTheme.typography.labelMedium)
                    }
                    Text("${providerTitle(saved.provider)} · ${saved.model} · $key", style = MaterialTheme.typography.titleMedium)
                    Text(saved.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onEdit) { Text(stringResource(CoreUiR.string.common_edit)) }
                if (saved.hasKey) OutlinedButton(onClick = actions.onClearKey) { Text(stringResource(R.string.aisettings_clear_key)) }
            }
        }
        if (state.onDevice == null && state.onDeviceAvailable) {
            OutlinedButton(onClick = actions.onOnDevice) { Text(stringResource(R.string.aisettings_phone_model_offline)) }
        }
        TextButton(onClick = actions.onReset) { Text(stringResource(R.string.aisettings_reset)) }
        Text(
            stringResource(R.string.aisettings_key_storage),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Wizard(state: AiSettingsUiState, actions: AiSettingsActions, modifier: Modifier) {
    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            item { Text(stringResource(R.string.aisettings_hub_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp)) }
            item { ProviderStep(state.provider, actions.onProvider, state.onDeviceAvailable, actions.onOnDevice, state.onDeviceInOtherBuild) }
            item { ConnectionStep(state, actions) }
            item { StepTitle(stringResource(R.string.aisettings_step_model), enabled = state.modelStepEnabled) }
            when {
                !state.modelStepEnabled -> item {
                    Text(
                        stringResource(R.string.aisettings_model_locked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
                state.manualModel -> item {
                    OutlinedTextField(
                        value = state.model,
                        onValueChange = actions.onModel,
                        label = { Text(stringResource(R.string.aisettings_model_id)) },
                        placeholder = { Text(stringResource(R.string.aisettings_model_id_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    )
                }
                else -> {
                    item {
                        val total = (state.check as? CheckState.Passed)?.models?.size ?: 0
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = actions.onQuery,
                            label = { Text(stringResource(R.string.aisettings_model_search)) },
                            singleLine = true,
                            supportingText = { Text(stringResource(R.string.aisettings_models_shown, state.visibleModels.size, total)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    items(state.visibleModels, key = { it.id }) { model ->
                        ModelRow(model, selected = model.id == state.model, onClick = { actions.onModel(model.id) })
                    }
                }
            }
        }
        BottomBar(state, actions)
    }
}

@Composable
private fun StepTitle(text: String, enabled: Boolean = true) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun ProviderStep(
    selected: LlmProviderKind,
    onProvider: (LlmProviderKind) -> Unit,
    onDeviceAvailable: Boolean = false,
    onOnDevice: () -> Unit = {},
    onDeviceInOtherBuild: Boolean = false,
) {
    Column {
        StepTitle(stringResource(R.string.aisettings_step_provider))
        Column(Modifier.selectableGroup()) {
            LlmProviderKind.entries.forEach { kind ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = kind == selected, onClick = { onProvider(kind) }, role = Role.RadioButton)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = kind == selected, onClick = null)
                    Text(providerTitle(kind), Modifier.padding(start = 8.dp))
                }
            }
            if (onDeviceAvailable) {
                // Not a cloud provider: its own screen (phone check → download → test); no key or URL needed.
                Row(
                    Modifier.fillMaxWidth().selectable(selected = false, onClick = onOnDevice, role = Role.Button).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = false, onClick = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(stringResource(R.string.aisettings_on_device))
                        Text(
                            stringResource(R.string.aisettings_on_device_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (onDeviceInOtherBuild) {
                // Cloud build: no item, just one dimmed line (not clickable).
                Text(
                    stringResource(R.string.aisettings_on_device_other_build),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ConnectionStep(state: AiSettingsUiState, actions: AiSettingsActions) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val checking = state.check is CheckState.Checking
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StepTitle(stringResource(R.string.aisettings_step_connection))
        if (state.provider == LlmProviderKind.OLLAMA) {
            Text(
                stringResource(R.string.aisettings_ollama_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.showUrlField) OutlinedTextField(
            value = state.baseUrl,
            onValueChange = actions.onBaseUrl,
            label = { Text(stringResource(R.string.aisettings_api_url)) },
            placeholder = { Text("http://192.168.1.5:1234/v1") },
            isError = state.urlError != null,
            supportingText = {
                Text(state.urlError?.asString() ?: stringResource(R.string.aisettings_api_url_help))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.showKeyField) OutlinedTextField(
            value = state.apiKey,
            onValueChange = actions.onApiKey,
            label = { Text(stringResource(if (state.provider.needsKey) R.string.aisettings_api_key else R.string.aisettings_api_key_optional)) },
            placeholder = { Text(stringResource(if (state.canReuseSavedKey) R.string.aisettings_key_saved_hint else R.string.aisettings_key_paste_hint)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { actions.onCheck() }),
            trailingIcon = {
                TextButton(onClick = {
                    scope.launch {
                        val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                        if (!text.isNullOrBlank()) actions.onApiKey(text.trim())
                    }
                }) { Text(stringResource(R.string.aisettings_paste)) }
            },
            supportingText = { Text(stringResource(R.string.aisettings_key_storage_short)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = actions.onCheck, enabled = !checking) { Text(stringResource(R.string.aisettings_check)) }
            if (checking) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.aisettings_checking))
            }
        }
        when (val check = state.check) {
            is CheckState.Passed -> Text("✓ ${check.message.asString()}", color = MaterialTheme.colorScheme.primary)
            is CheckState.Failed -> Text(check.message.asString(), color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
    }
}

@Composable
private fun ModelRow(model: ModelInfo, selected: Boolean, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.padding(start = 8.dp)) {
                Text(model.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (model.recommended) FontWeight.Medium else null)
                val tags = buildList {
                    if (model.name != model.id) add(model.id)
                    model.contextLength?.let { add(stringResource(R.string.aisettings_context, contextLabel(it))) }
                    if (model.isFree == true) add(stringResource(R.string.aisettings_free))
                    if (model.recommended) add(stringResource(R.string.aisettings_recommended))
                }
                if (tags.isNotEmpty()) {
                    Text(
                        tags.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

private fun contextLabel(tokens: Long): String = when {
    tokens >= 1_000_000 && tokens % 1_000_000 < 50_000 -> "${tokens / 1_000_000}M"
    tokens >= 1_000_000 -> String.format(java.util.Locale.getDefault(), "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000 -> "${tokens / 1_000}K"
    else -> tokens.toString()
}

@Composable
private fun BottomBar(state: AiSettingsUiState, actions: AiSettingsActions) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (state.model.isNotBlank() && state.modelStepEnabled) stringResource(R.string.aisettings_model_selected, state.model)
                else stringResource(R.string.aisettings_model_none),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.saved != null) TextButton(onClick = actions.onCancelEdit) { Text(stringResource(CoreUiR.string.common_cancel)) }
            Button(onClick = actions.onSave, enabled = state.canSave) { Text(stringResource(CoreUiR.string.common_save)) }
        }
    }
}

/** Provider names are brand names except «Other», which is localized. */
@Composable
internal fun providerTitle(kind: LlmProviderKind): String =
    if (kind == LlmProviderKind.OTHER) stringResource(R.string.aisettings_provider_other) else kind.title

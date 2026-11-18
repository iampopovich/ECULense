package io.github.iampopovich.eculense.feature.aisettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.ai.LlmProviderKind
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun AiSettingsRoute(viewModel: AiSettingsViewModel = koinViewModel()) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    AiSettingsScreen(
        form = form,
        onProvider = viewModel::selectProvider,
        onChange = viewModel::update,
        onSave = viewModel::saveAndTest,
        onClearKey = viewModel::clearKey,
    )
}

@Composable
internal fun AiSettingsScreen(
    form: AiSettingsForm,
    onProvider: (LlmProviderKind) -> Unit,
    onChange: ((AiSettingsForm) -> AiSettingsForm) -> Unit,
    onSave: () -> Unit,
    onClearKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ИИ-помощник", style = MaterialTheme.typography.headlineSmall)
        Text("Провайдер", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
            LlmProviderKind.entries.forEach { kind ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = kind == form.provider, onClick = { onProvider(kind) }, role = Role.RadioButton)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = kind == form.provider, onClick = null)
                    Text(kind.title, Modifier.padding(start = 8.dp))
                }
            }
        }
        OutlinedTextField(
            value = form.model,
            onValueChange = { v -> onChange { it.copy(model = v) } },
            label = { Text("Модель") },
            placeholder = { Text(form.provider.modelHint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val defaultBaseUrl = form.provider.defaultBaseUrl
        if (defaultBaseUrl != null) {
            OutlinedTextField(
                value = form.baseUrl,
                onValueChange = { v -> onChange { it.copy(baseUrl = v) } },
                label = { Text("Адрес сервера") },
                placeholder = { Text(defaultBaseUrl) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = form.apiKey,
            onValueChange = { v -> onChange { it.copy(apiKey = v) } },
            label = { Text(if (form.provider.needsKey) "API-ключ" else "API-ключ (если нужен)") },
            placeholder = { Text(if (form.hasSavedKey) "сохранён" else "вставьте ключ") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            supportingText = { Text("Хранится зашифрованным ключом Android Keystore и не попадает в бэкап.") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = !form.busy) { Text("Сохранить и проверить") }
            if (form.hasSavedKey) OutlinedButton(onClick = onClearKey, enabled = !form.busy) { Text("Удалить ключ") }
        }
        form.status?.let { status ->
            Text(status, color = if (status.startsWith("Ошибка")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
}

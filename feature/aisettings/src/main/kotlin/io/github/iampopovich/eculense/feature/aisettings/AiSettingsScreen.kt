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
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** Действия экрана; по умолчанию пустые — удобно в UI-тестах. */
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
)

@Composable
internal fun AiSettingsRoute(viewModel: AiSettingsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
        ),
    )
}

@Composable
internal fun AiSettingsScreen(state: AiSettingsUiState, actions: AiSettingsActions, modifier: Modifier = Modifier) {
    if (!state.loaded) return
    val saved = state.saved
    if (!state.editing && saved != null) {
        SavedSummary(saved, actions, modifier)
    } else {
        Wizard(state, actions, modifier)
    }
}

@Composable
private fun SavedSummary(saved: SavedConfig, actions: AiSettingsActions, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ИИ-помощник", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val key = when {
                    !saved.provider.usesKey -> "без ключа"
                    saved.hasKey -> "ключ сохранён"
                    saved.provider.needsKey -> "ключ не задан"
                    else -> "без ключа"
                }
                Text("${saved.provider.title} · ${saved.model} · $key", style = MaterialTheme.typography.titleMedium)
                Text(saved.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onEdit) { Text("Изменить") }
            if (saved.hasKey) OutlinedButton(onClick = actions.onClearKey) { Text("Удалить ключ") }
        }
        TextButton(onClick = actions.onReset) { Text("Сбросить настройки ИИ") }
        Text(
            "Ключ хранится зашифрованным ключом Android Keystore и не попадает в бэкап.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Wizard(state: AiSettingsUiState, actions: AiSettingsActions, modifier: Modifier) {
    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            item { Text("ИИ-помощник", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp)) }
            item { ProviderStep(state.provider, actions.onProvider) }
            item { ConnectionStep(state, actions) }
            item { StepTitle("3. Модель", enabled = state.modelStepEnabled) }
            when {
                !state.modelStepEnabled -> item {
                    Text(
                        "Сначала проверьте подключение — список моделей придёт с сервера.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
                state.manualModel -> item {
                    OutlinedTextField(
                        value = state.model,
                        onValueChange = actions.onModel,
                        label = { Text("ID модели") },
                        placeholder = { Text("например, qwen2.5-7b-instruct") },
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
                            label = { Text("Поиск модели") },
                            singleLine = true,
                            supportingText = { Text("Показано ${state.visibleModels.size} из $total") },
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
private fun ProviderStep(selected: LlmProviderKind, onProvider: (LlmProviderKind) -> Unit) {
    Column {
        StepTitle("1. Провайдер")
        Column(Modifier.selectableGroup()) {
            LlmProviderKind.entries.forEach { kind ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = kind == selected, onClick = { onProvider(kind) }, role = Role.RadioButton)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = kind == selected, onClick = null)
                    Text(kind.title, Modifier.padding(start = 8.dp))
                }
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
        StepTitle("2. Подключение")
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = actions.onBaseUrl,
            label = { Text("Адрес API") },
            placeholder = if (state.provider == LlmProviderKind.OTHER) {
                { Text("http://192.168.1.5:1234/v1") }
            } else {
                null
            },
            isError = state.urlError != null,
            supportingText = {
                Text(
                    state.urlError ?: when (state.provider) {
                        LlmProviderKind.OTHER ->
                            "Полный OpenAI-совместимый адрес: LM Studio http://IP:1234/v1 · vLLM http://IP:8000/v1 · llama.cpp http://IP:8080/v1"
                        LlmProviderKind.OLLAMA -> "Ollama на ПК в сети: http://IP:11434 (по умолчанию — сам телефон)"
                        else -> "Адрес по умолчанию из Koog; меняйте только для прокси"
                    },
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.showKeyField) OutlinedTextField(
            value = state.apiKey,
            onValueChange = actions.onApiKey,
            label = { Text(if (state.provider.needsKey) "API-ключ" else "API-ключ (если нужен)") },
            placeholder = { Text(if (state.canReuseSavedKey) "сохранён — можно не вводить" else "вставьте ключ") },
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
                }) { Text("Вставить") }
            },
            supportingText = { Text("Хранится зашифрованным ключом Android Keystore и не попадает в бэкап.") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = actions.onCheck, enabled = !checking) { Text("Проверить подключение") }
            if (checking) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Проверяю…")
            }
        }
        when (val check = state.check) {
            is CheckState.Passed -> Text("✓ ${check.message}", color = MaterialTheme.colorScheme.primary)
            is CheckState.Failed -> Text(check.message, color = MaterialTheme.colorScheme.error)
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
                    model.contextLength?.let { add("${contextLabel(it)} контекст") }
                    if (model.isFree == true) add("бесплатно")
                    if (model.recommended) add("★ рекомендуем")
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
    tokens >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fM", tokens / 1_000_000.0)
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
                if (state.model.isNotBlank() && state.modelStepEnabled) "Модель: ${state.model}" else "Модель не выбрана",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.saved != null) TextButton(onClick = actions.onCancelEdit) { Text("Отмена") }
            Button(onClick = actions.onSave, enabled = state.canSave) { Text("Сохранить") }
        }
    }
}

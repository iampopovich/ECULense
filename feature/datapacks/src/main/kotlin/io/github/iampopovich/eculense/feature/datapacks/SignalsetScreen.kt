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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.obdb.SignalsetResult
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun SignalsetRoute(viewModel: SignalsetViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SignalsetScreen(state, onYear = viewModel::setYear, onCheck = viewModel::check)
}

/**
 * Сигналсет OBDb для активной машины: только просмотр. Год можно поменять, чтобы посмотреть покрытое поколение
 * (проверка формата); опрос расширенных PID — бэклог 1.24 п. 6–7.
 */
@Composable
internal fun SignalsetScreen(state: SignalsetUiState, onYear: (String) -> Unit, onCheck: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Машина: ${state.carTitle ?: "не выбрана"}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Опрос расширенных PID пока не поддерживается: нужны заголовки ЭБУ (ATH1/ATSH) и сервисы 21/22. Здесь — что известно OBDb о вашей модели.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.year, onValueChange = onYear, label = { Text("Год") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(120.dp),
                    )
                    Button(onClick = onCheck, enabled = !state.loading) { Text("Проверить OBDb") }
                }
                if (state.loading) LinearProgressIndicator()
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        when (val r = state.result) {
            null -> Unit
            is SignalsetResult.NeedsProfile -> item { Text("В профиле машины не указано: ${r.missing.joinToString()}. Заполните в «Автомобиль».") }
            is SignalsetResult.NoRepository -> item { Text("В OBDb нет репозитория ${r.repo} — для этой модели сигналов пока нет.") }
            is SignalsetResult.NotCovered -> item {
                Text(
                    "OBDb (${r.repo}) не покрывает ${r.year} год" +
                        (r.generation?.let { " — поколение «${it.name}», ${it.years}" } ?: "") +
                        ": для него нет данных с реальных машин. Сигналы OBDb рассчитаны на CAN-машины более поздних поколений; " +
                        "для этой машины расширенные PID появятся из пользовательских файлов или собственного вклада в OBDb.",
                )
            }
            is SignalsetResult.Covered -> {
                item {
                    Text(
                        "${r.repo}, ${r.year}" + (r.generation?.let { " (${it.name})" } ?: "") +
                            ": команд ${r.commands.size}, сигналов ${r.signalCount}, ЭБУ ${r.ecus.size}. Версия ${r.version}. CC BY-SA 4.0.",
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

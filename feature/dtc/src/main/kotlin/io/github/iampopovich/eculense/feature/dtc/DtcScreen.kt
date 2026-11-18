package io.github.iampopovich.eculense.feature.dtc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.navigation.AiChatDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun DtcRoute(viewModel: DtcViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    DtcScreen(
        state = state,
        onRead = viewModel::read,
        onClear = viewModel::clear,
        onAskAi = { item ->
            navigator.navigate(AiChatDestination("Объясни код ошибки ${item.dtc.code} (${item.description ?: "без описания"}): причины, что проверить, насколько срочно."))
        },
    )
}

@Composable
internal fun DtcScreen(
    state: DtcUiState,
    onRead: () -> Unit,
    onClear: () -> Unit,
    onAskAi: (DtcItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRead, enabled = !state.loading) { Text("Прочитать ошибки") }
            OutlinedButton(onClick = { confirmClear = true }, enabled = !state.loading && state.items.isNotEmpty()) { Text("Сбросить") }
        }
        if (state.loading) LinearProgressIndicator()
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn {
            items(state.items, key = { it.dtc.code + it.dtc.kind }) { item ->
                ListItem(
                    headlineContent = { Text("${item.dtc.code} · ${item.dtc.kind.label}") },
                    supportingContent = { Text(item.description ?: "Нет описания в базе (возможно, заводской код)") },
                    trailingContent = if (state.canAskAi) {
                        { TextButton(onClick = { onAskAi(item) }) { Text("Спросить ИИ") } }
                    } else {
                        null
                    },
                )
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Сбросить ошибки?") },
            text = { Text("Двигатель заглушен, зажигание включено? Сброс сотрёт коды и стоп-кадры, мониторы готовности обнулятся (машина не пройдёт экологический тест до полного ездового цикла). Текущие коды сохранятся в истории.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("Сбросить") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

private val DtcKind.label: String
    get() = when (this) {
        DtcKind.STORED -> "сохранённая"
        DtcKind.PENDING -> "ожидающая"
        DtcKind.PERMANENT -> "постоянная"
    }

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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.dtcdb.DtcInfo
import io.github.iampopovich.eculense.core.dtcdb.DtcSource
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.navigation.AiChatDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.ui.EcuDates
import org.koin.compose.viewmodel.koinViewModel

@Composable
internal fun DtcRoute(viewModel: DtcViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    DtcScreen(
        state = state,
        onRead = viewModel::read,
        onClear = viewModel::clear,
        onAskAi = { code, info -> navigator.navigate(AiChatDestination(askAiPrompt(code, info))) },
    )
}

@Composable
internal fun DtcScreen(
    state: DtcUiState,
    onRead: () -> Unit,
    onClear: () -> Unit,
    onAskAi: (String, DtcInfo) -> Unit,
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
        if (state.checked && state.items.isEmpty() && !state.loading) {
            Text("Ошибок нет: ЭБУ не сообщил ни одного кода.")
        }
        LazyColumn {
            items(state.items, key = { "now-" + it.dtc.code + it.dtc.kind }) { item ->
                ListItem(
                    headlineContent = { Text("${item.dtc.code} · ${item.dtc.kind.label}") },
                    supportingContent = { DtcInfoText(item.info) },
                    trailingContent = askAi(state.canAskAi) { onAskAi(item.dtc.code, item.info) },
                )
            }
            if (state.history.isNotEmpty()) {
                item(key = "history-header") {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("История кодов этой машины", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                items(state.history, key = { "history-" + it.code }) { h ->
                    ListItem(
                        headlineContent = { Text("${h.code} · ${h.kind.label}") },
                        overlineContent = { Text("${h.count} ${times(h.count)}, последний раз ${EcuDates.date(h.lastMs)}") },
                        supportingContent = { DtcInfoText(h.info) },
                        trailingContent = askAi(state.canAskAi) { onAskAi(h.code, h.info) },
                    )
                }
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

private fun askAi(enabled: Boolean, onClick: () -> Unit): (@Composable () -> Unit)? =
    if (enabled) {
        { TextButton(onClick = onClick) { Text("Спросить ИИ") } }
    } else {
        null
    }

/** Название, а для OBDex — ещё две частые причины и симптомы; источник подписан (чья расшифровка). */
@Composable
internal fun DtcInfoText(info: DtcInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (info.source == DtcSource.OEM_UNKNOWN || info.title == null) {
            Text(info.text, fontStyle = FontStyle.Italic)
        } else {
            Text(info.text)
        }
        if (info.causes.isNotEmpty()) {
            Text("Причины: " + info.causes.take(2).joinToString("; "), style = MaterialTheme.typography.bodySmall)
        }
        if (info.symptoms.isNotEmpty()) {
            Text("Симптомы: " + info.symptoms.take(2).joinToString("; "), style = MaterialTheme.typography.bodySmall)
        }
        info.sourceLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

internal val DtcInfo.sourceLabel: String?
    get() = when (source) {
        DtcSource.OBDEX -> "Источник: OBDex (CC0)"
        DtcSource.BUILTIN -> "Встроенная база (скачайте OBDex для причин и симптомов)"
        DtcSource.OEM -> "Заводская база ${manufacturer.orEmpty()} (Wal33D)"
        DtcSource.OEM_UNKNOWN, DtcSource.UNKNOWN -> null
    }

private fun times(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> "раз"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "раза"
    else -> "раз"
}

private val DtcKind.label: String
    get() = when (this) {
        DtcKind.STORED -> "сохранённая"
        DtcKind.PENDING -> "ожидающая"
        DtcKind.PERMANENT -> "постоянная"
    }

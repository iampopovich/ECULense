package io.github.iampopovich.eculense.feature.aichat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iampopovich.eculense.core.ai.ChatMessage
import io.github.iampopovich.eculense.core.navigation.AiSettingsDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun ChatRoute(prefill: String?, viewModel: ChatViewModel = koinViewModel { parametersOf(prefill) }) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    ChatScreen(
        state = state,
        onSend = viewModel::send,
        onOpenSettings = { navigator.navigate(AiSettingsDestination) },
    )
}

@Composable
internal fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val lastLength = state.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(state.messages.size, lastLength, state.error) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.scrollToItem(count - 1)
    }
    Column(modifier.fillMaxSize().aboveKeyboard().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ИИ-помощник", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(painterResource(R.drawable.ic_tune), contentDescription = "Настройки ИИ")
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.messages.isEmpty() && state.error == null) {
                item {
                    Text(
                        "Спросите о машине или коде ошибки. ИИ не видит VIN и координаты; ответы — подсказка, а не диагноз.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            itemsIndexed(state.messages) { index, message ->
                val typing = state.streaming && index == state.messages.lastIndex && message.text.isEmpty()
                Bubble(message, typing)
            }
            state.error?.let { error ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onOpenSettings) { Text("Настроить ИИ") }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Вопрос") },
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    onSend(input)
                    input = ""
                },
                enabled = !state.streaming && input.isNotBlank(),
            ) { Text("Отправить") }
        }
    }
}

/**
 * Поле ввода над клавиатурой. Экран живёт внутри Scaffold над нижним меню (edge-to-edge, adjustResize не сжимает окно),
 * поэтому imePadding() не подходит: отступ = на сколько клавиатура перекрывает нижний край самого экрана.
 */
@Composable
private fun Modifier.aboveKeyboard(): Modifier {
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val imeBottom = WindowInsets.ime.getBottom(density)
    var gapBelow by remember { mutableIntStateOf(0) }
    val overlap = (imeBottom - gapBelow).coerceAtLeast(0)
    return this
        .onGloballyPositioned { gapBelow = windowHeight - it.boundsInWindow().bottom.toInt() }
        .padding(bottom = with(density) { overlap.toDp() })
}

@Composable
private fun Bubble(message: ChatMessage, typing: Boolean) {
    val mine = message.role == ChatMessage.Role.USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text = if (typing) "…" else message.text,
            color = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

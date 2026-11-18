package io.github.iampopovich.eculense.feature.aichat

import android.content.ClipData
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import io.github.iampopovich.eculense.core.ai.ChatMessage
import io.github.iampopovich.eculense.core.navigation.AiSettingsDestination
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.ui.aboveKeyboard
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Подсказки пустого чата: подставляются в поле ввода (не отправляются сами — запрос стоит денег). */
internal val CHAT_SUGGESTIONS = listOf("Объясни мои ошибки", "Как прошла последняя поездка?")

@Composable
internal fun ChatRoute(prefill: String?, viewModel: ChatViewModel = koinViewModel { parametersOf(prefill) }) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    ChatScreen(
        state = state,
        onSend = viewModel::send,
        onOpenSettings = { navigator.navigate(AiSettingsDestination) },
        onStop = viewModel::stop,
        onRetry = viewModel::retry,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatScreen(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onStop: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    // Ответ растёт и после того, как текст пришёл (Markdown дописывает блоки асинхронно), поэтому держимся за низ
    // списка по фактическому размеру последнего элемента, а не по длине текста.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            // только пока последний элемент виден: пролистанную вверх историю не дёргаем
            info.visibleItemsInfo.lastOrNull()?.takeIf { it.index == info.totalItemsCount - 1 }?.let { it.index to it.size }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { (index, _) -> listState.scrollToItem(index, Int.MAX_VALUE) }
    }
    val lastLength = state.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(state.messages.size, lastLength, state.error, state.streaming) {
        val count = listState.layoutInfo.totalItemsCount
        // Большое смещение упирается в конец списка: видно низ растущего ответа, а не его начало.
        if (count > 0) listState.scrollToItem(count - 1, Int.MAX_VALUE)
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.messages.isEmpty() && state.error == null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            "Спросите о машине или коде ошибки. ИИ не видит VIN и координаты; ответы — подсказка, а не диагноз.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CHAT_SUGGESTIONS.forEach { s -> SuggestionChip(onClick = { input = s }, label = { Text(s) }) }
                        }
                    }
                }
            }
            itemsIndexed(state.messages) { index, message ->
                val last = index == state.messages.lastIndex
                when {
                    message.role == ChatMessage.Role.USER -> UserBubble(message.text)
                    last && state.awaitingFirstToken -> TypingIndicator()
                    else -> AssistantMessage(message.text, streaming = state.streaming && last, stopped = state.stoppedIndex == index)
                }
            }
            state.error?.let { error ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.canRetry) OutlinedButton(onClick = onRetry) { Text("Повторить") }
                            TextButton(onClick = onOpenSettings) { Text("Настроить ИИ") }
                        }
                    }
                }
            }
        }
        ChatComposer(
            value = input,
            onValueChange = { input = it },
            streaming = state.streaming,
            onSend = {
                onSend(input)
                input = ""
            },
            onStop = onStop,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
    }
}

/** Вопрос пользователя — тональный «пузырь» справа, обычный текст без Markdown. */
@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    MaterialTheme.colorScheme.secondaryContainer,
                    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** Ответ ИИ — на всю ширину без пузыря (как в Gemini/ChatGPT), Markdown + «Копировать» после завершения. */
@Composable
private fun AssistantMessage(text: String, streaming: Boolean, stopped: Boolean) {
    val shown = HtmlEntities.decode(text, complete = !streaming)
    Column(Modifier.fillMaxWidth()) {
        AssistantMarkdown(shown)
        if (!streaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CopyButton(shown)
                if (stopped) {
                    Text("Ответ остановлен", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Индикатор «печатает» до первого куска ответа: три точки с бегущей прозрачностью. */
@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 12.dp).semantics { contentDescription = "ИИ печатает" },
    ) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(8.dp).alpha(alpha).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        }
    }
}

/**
 * Ответ ИИ как Markdown. Состояние парсера потоковое (append-only): при каждом новом куске текста
 * дописываем только хвост, уже разобранные блоки не перерисовываются.
 */
@Composable
private fun AssistantMarkdown(text: String) {
    val markdown = rememberStreamingMarkdownState()
    LaunchedEffect(markdown, text) {
        val have = markdown.content.length
        if (text.length > have) markdown.append(text.substring(have))
    }
    // Заголовки по умолчанию — display-стили (огромные для чата): сжимаем до title/body.
    val type = MaterialTheme.typography
    Markdown(
        streamingMarkdownState = markdown,
        typography = markdownTypography(
            h1 = type.titleLarge,
            h2 = type.titleLarge,
            h3 = type.titleMedium,
            h4 = type.titleMedium,
            h5 = type.titleSmall,
            h6 = type.titleSmall,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CopyButton(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // 48 dp — минимальная зона касания; сама иконка 20 dp
    IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("ECULense", text))) } }) {
        Icon(
            painterResource(R.drawable.ic_copy),
            contentDescription = "Копировать ответ",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

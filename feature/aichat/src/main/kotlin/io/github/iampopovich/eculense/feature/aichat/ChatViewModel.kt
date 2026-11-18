package io.github.iampopovich.eculense.feature.aichat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.ai.ChatMessage
import io.github.iampopovich.eculense.core.ai.LlmGateway
import io.github.iampopovich.eculense.core.ai.SystemPrompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val error: String? = null,
    /** Индекс ответа, который пользователь остановил кнопкой «Стоп» (подпись «Ответ остановлен»). */
    val stoppedIndex: Int? = null,
) {
    /** Запрос ушёл, но первый кусок ответа ещё не пришёл — показываем индикатор «печатает». */
    val awaitingFirstToken: Boolean
        get() = streaming && messages.lastOrNull()?.let { it.role == ChatMessage.Role.ASSISTANT && it.text.isEmpty() } == true

    /** Можно повторить: последний вопрос остался без ответа из-за ошибки. */
    val canRetry: Boolean
        get() = !streaming && error != null && messages.lastOrNull()?.role == ChatMessage.Role.USER
}

internal class ChatViewModel(private val gateway: LlmGateway, prefill: String?) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private var job: Job? = null
    private var generation = 0

    init {
        if (!prefill.isNullOrBlank()) send(prefill)
    }

    fun send(text: String) {
        if (text.isBlank() || _uiState.value.streaming) return
        ask(_uiState.value.messages + ChatMessage(ChatMessage.Role.USER, text.trim()))
    }

    /** Повтор вопроса после ошибки — без повторного ввода. */
    fun retry() {
        if (!_uiState.value.canRetry) return
        ask(_uiState.value.messages)
    }

    /** «Стоп»: обрываем поток; уже полученный текст остаётся, пустой пузырь ответа убирается. */
    fun stop() {
        if (!_uiState.value.streaming) return
        job?.cancel()
        job = null
        _uiState.update { s ->
            val last = s.messages.lastOrNull()
            val messages = if (last?.role == ChatMessage.Role.ASSISTANT && last.text.isEmpty()) s.messages.dropLast(1) else s.messages
            s.copy(
                messages = messages,
                streaming = false,
                stoppedIndex = messages.lastIndex.takeIf { messages.lastOrNull()?.role == ChatMessage.Role.ASSISTANT },
            )
        }
    }

    private fun ask(history: List<ChatMessage>) {
        _uiState.value = ChatUiState(history + ChatMessage(ChatMessage.Role.ASSISTANT, ""), streaming = true, stoppedIndex = _uiState.value.stoppedIndex)
        val gen = ++generation
        job = viewModelScope.launch {
            try {
                gateway.stream(SystemPrompts.MECHANIC, history).collect { chunk ->
                    _uiState.update { s ->
                        val last = s.messages.last()
                        s.copy(messages = s.messages.dropLast(1) + last.copy(text = last.text + chunk))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Ошибка ИИ", messages = it.messages.filterNot { m -> m.text.isEmpty() }) }
            } finally {
                // остановленный поток не должен сбросить состояние уже начатого следующего запроса
                if (gen == generation) _uiState.update { it.copy(streaming = false) }
            }
        }
    }
}

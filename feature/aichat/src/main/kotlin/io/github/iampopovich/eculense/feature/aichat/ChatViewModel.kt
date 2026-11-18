package io.github.iampopovich.eculense.feature.aichat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.ai.ChatEvent
import io.github.iampopovich.eculense.core.ai.ChatMessage
import io.github.iampopovich.eculense.core.ai.LlmGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

internal data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val error: String? = null,
    /** Индекс ответа, который пользователь остановил кнопкой «Стоп» (подпись «Ответ остановлен»). */
    val stoppedIndex: Int? = null,
    /** Подключены инструменты данных машины — показываем переключатель. */
    val carDataAvailable: Boolean = false,
    /** «Использовать данные моей машины». */
    val useCarData: Boolean = false,
    /** Что сейчас смотрит ИИ («ИИ смотрит ошибки…») — пока идёт вызов инструмента. */
    val toolStatus: String? = null,
) {
    /** Запрос ушёл, но первый кусок ответа ещё не пришёл — показываем индикатор «печатает». */
    val awaitingFirstToken: Boolean
        get() = streaming && messages.lastOrNull()?.let { it.role == ChatMessage.Role.ASSISTANT && it.text.isEmpty() } == true

    /** Можно повторить: последний вопрос остался без ответа из-за ошибки. */
    val canRetry: Boolean
        get() = !streaming && error != null && messages.lastOrNull()?.role == ChatMessage.Role.USER
}

/**
 * Чат через агента Koog ([LlmGateway.chat]): история диалога — у агента (ChatMemory по conversationId),
 * здесь — только то, что видно на экране. Ответ стримится; во время вызова инструментов — подпись «ИИ смотрит…».
 */
internal class ChatViewModel(private val gateway: LlmGateway, prefill: String?) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState(carDataAvailable = gateway.hasVehicleTools, useCarData = gateway.hasVehicleTools))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    private val conversationId = UUID.randomUUID().toString()

    init {
        if (!prefill.isNullOrBlank()) send(prefill)
    }

    fun send(text: String) {
        if (text.isBlank() || _uiState.value.streaming) return
        val question = text.trim()
        ask(_uiState.value.messages + ChatMessage(ChatMessage.Role.USER, question), question)
    }

    /** Повтор вопроса после ошибки — без повторного ввода (неудачный ход агент в историю не записал). */
    fun retry() {
        val s = _uiState.value
        if (!s.canRetry) return
        ask(s.messages, s.messages.last().text)
    }

    fun setUseCarData(enabled: Boolean) {
        _uiState.update { it.copy(useCarData = enabled && it.carDataAvailable) }
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
                toolStatus = null,
                stoppedIndex = messages.lastIndex.takeIf { messages.lastOrNull()?.role == ChatMessage.Role.ASSISTANT },
            )
        }
    }

    private fun ask(history: List<ChatMessage>, question: String) {
        _uiState.update { it.copy(messages = history + ChatMessage(ChatMessage.Role.ASSISTANT, ""), streaming = true, error = null, toolStatus = null) }
        val gen = ++generation
        val useCarData = _uiState.value.useCarData
        job = viewModelScope.launch {
            try {
                gateway.chat(conversationId, question, useCarData).collect { event ->
                    _uiState.update { s ->
                        when (event) {
                            is ChatEvent.ToolCall -> s.copy(toolStatus = event.label)
                            is ChatEvent.Text -> {
                                val last = s.messages.last()
                                s.copy(messages = s.messages.dropLast(1) + last.copy(text = last.text + event.delta), toolStatus = null)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Ошибка ИИ", messages = it.messages.filterNot { m -> m.text.isEmpty() }) }
            } finally {
                // остановленный поток не должен сбросить состояние уже начатого следующего запроса
                if (gen == generation) _uiState.update { it.copy(streaming = false, toolStatus = null) }
            }
        }
    }
}

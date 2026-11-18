package io.github.iampopovich.eculense.feature.aichat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.ai.ChatMessage
import io.github.iampopovich.eculense.core.ai.LlmGateway
import io.github.iampopovich.eculense.core.ai.SystemPrompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val error: String? = null,
)

internal class ChatViewModel(private val gateway: LlmGateway, prefill: String?) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        if (!prefill.isNullOrBlank()) send(prefill)
    }

    fun send(text: String) {
        if (text.isBlank() || _uiState.value.streaming) return
        val history = _uiState.value.messages + ChatMessage(ChatMessage.Role.USER, text.trim())
        _uiState.value = ChatUiState(history + ChatMessage(ChatMessage.Role.ASSISTANT, ""), streaming = true)
        viewModelScope.launch {
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
                _uiState.update { it.copy(streaming = false) }
            }
        }
    }
}

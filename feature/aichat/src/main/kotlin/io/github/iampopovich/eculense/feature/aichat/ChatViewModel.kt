package io.github.iampopovich.eculense.feature.aichat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.ai.ChatEvent
import io.github.iampopovich.eculense.core.ai.ChatMessage
import io.github.iampopovich.eculense.core.ai.ChatPrefs
import io.github.iampopovich.eculense.core.ai.ExpertNote
import io.github.iampopovich.eculense.core.ai.LlmGateway
import io.github.iampopovich.eculense.core.ai.ProviderInfo
import io.github.iampopovich.eculense.core.ui.UiText
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Question awaiting consent: which data goes to which provider. */
internal data class ConsentRequest(val provider: ProviderInfo, val text: String)

/** «Над ответом работали: …» and expandable «Заметки экспертов». */
internal data class ExpertsInfo(val roles: List<UiText>, val notes: List<ExpertNote>)

internal data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val error: UiText? = null,
    /** Index of the answer the user stopped with the «Стоп» button (label «Ответ остановлен»). */
    val stoppedIndex: Int? = null,
    /** Car data tools are connected: show the toggle. */
    val carDataAvailable: Boolean = false,
    /** «Использовать данные моей машины». */
    val useCarData: Boolean = false,
    /** What the AI is looking at now («ИИ смотрит ошибки…») while a tool call is running. */
    val toolStatus: UiText? = null,
    /** Chat runs on the on-phone model: an honest note about quality and speed. */
    val onDevice: Boolean = false,
    /** Waiting for consent to send car data to this provider. */
    val consent: ConsentRequest? = null,
    /** Text to put back into the input field (consent dialog was dismissed). */
    val draft: String? = null,
    /**
     * Who worked on the answer, keyed by the answer message index. Only for the current display: history (ai_chat.db) stores
     * just the final answer; after a restart the label and notes are gone.
     */
    val experts: Map<Int, ExpertsInfo> = emptyMap(),
) {
    /** Request sent but the first answer chunk hasn't arrived yet: show the «печатает» indicator. */
    val awaitingFirstToken: Boolean
        get() = streaming && messages.lastOrNull()?.let { it.role == ChatMessage.Role.ASSISTANT && it.text.isEmpty() } == true

    /** Retry available: the last question got no answer because of an error. */
    val canRetry: Boolean
        get() = !streaming && error != null && messages.lastOrNull()?.role == ChatMessage.Role.USER
}

/**
 * Chat via the Koog agent ([LlmGateway.chat]): dialog history lives in the agent (ChatMemory by conversationId),
 * here only what's visible on screen. The answer is streamed; during tool calls the label «ИИ смотрит…» is shown.
 */
internal class ChatViewModel(
    private val gateway: LlmGateway,
    private val prefs: ChatPrefs,
    prefill: String?,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState(carDataAvailable = gateway.hasVehicleTools, useCarData = gateway.hasVehicleTools))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    private var conversationId = UUID.randomUUID().toString()

    init {
        viewModelScope.launch {
            val saved = prefs.useCarData()
            // The dialog survives restarts: id in settings, texts in ai_chat.db (ChatMemory).
            conversationId = prefs.conversationId() ?: conversationId.also { prefs.setConversationId(it) }
            val history = runCatching { gateway.history(conversationId) }.getOrDefault(emptyList())
            val onDevice = runCatching { gateway.provider()?.onDevice == true }.getOrDefault(false)
            _uiState.update { it.copy(useCarData = (saved ?: true) && it.carDataAvailable, messages = history + it.messages, onDevice = onDevice) }
            if (!prefill.isNullOrBlank()) send(prefill)
        }
    }

    /** «модель на телефоне» note, recomputed on every screen show: the provider is changed in settings while the VM lives on. */
    fun refreshProvider() {
        viewModelScope.launch {
            val onDevice = runCatching { gateway.provider()?.onDevice == true }.getOrDefault(false)
            _uiState.update { it.copy(onDevice = onDevice) }
        }
    }

    /** «Новый диалог»: the current conversation is deleted, the AI no longer sees it. */
    fun newDialog() {
        if (_uiState.value.streaming) stop()
        val old = conversationId
        conversationId = UUID.randomUUID().toString()
        _uiState.update { it.copy(messages = emptyList(), error = null, stoppedIndex = null, toolStatus = null, consent = null, experts = emptyMap()) }
        viewModelScope.launch {
            prefs.setConversationId(conversationId)
            gateway.deleteConversation(old)
        }
    }

    /**
     * Before car data is first sent to a cloud provider, a consent dialog (what is sent and where);
     * without consent the question is not sent.
     */
    fun send(text: String) {
        if (text.isBlank() || _uiState.value.streaming || _uiState.value.consent != null) return
        val question = text.trim()
        viewModelScope.launch {
            if (_uiState.value.useCarData) {
                val provider = gateway.provider()
                // on-phone model: data doesn't leave the device, no consent needed
                if (provider != null && !provider.onDevice && !prefs.hasConsent(provider.key)) {
                    _uiState.update { it.copy(consent = ConsentRequest(provider, question)) }
                    return@launch
                }
            }
            ask(_uiState.value.messages + ChatMessage(ChatMessage.Role.USER, question), question)
        }
    }

    /** «Разрешить»: remember consent for this provider and send the question with car data. */
    fun acceptConsent() {
        val request = _uiState.value.consent ?: return
        viewModelScope.launch {
            prefs.grantConsent(request.provider.key)
            _uiState.update { it.copy(consent = null) }
            ask(_uiState.value.messages + ChatMessage(ChatMessage.Role.USER, request.text), request.text)
        }
    }

    /** «Без данных машины»: the toggle is turned off, the question is sent without tools. */
    fun declineConsent() {
        val request = _uiState.value.consent ?: return
        _uiState.update { it.copy(consent = null, useCarData = false) }
        viewModelScope.launch { prefs.setUseCarData(false) }
        ask(_uiState.value.messages + ChatMessage(ChatMessage.Role.USER, request.text), request.text)
    }

    /** Dialog dismissed: send nothing, text goes back into the input field. */
    fun cancelConsent() {
        val request = _uiState.value.consent ?: return
        _uiState.update { it.copy(consent = null, draft = request.text) }
    }

    fun draftConsumed() {
        _uiState.update { it.copy(draft = null) }
    }

    /** Retry the question after an error without retyping (the agent didn't record the failed turn in history). */
    fun retry() {
        val s = _uiState.value
        if (!s.canRetry) return
        ask(s.messages, s.messages.last().text)
    }

    fun setUseCarData(enabled: Boolean) {
        if (!_uiState.value.carDataAvailable) return
        _uiState.update { it.copy(useCarData = enabled) }
        viewModelScope.launch { prefs.setUseCarData(enabled) }
    }

    /** «Стоп»: cut the stream; text already received stays, an empty answer bubble is removed. */
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
                            is ChatEvent.ToolCall -> s.copy(toolStatus = ChatTexts.toolCall(event))
                            is ChatEvent.Status -> s.copy(toolStatus = ChatTexts.status(event))
                            is ChatEvent.Experts -> s.copy(experts = s.experts + (s.messages.lastIndex to ChatTexts.experts(event)))
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
                _uiState.update { it.copy(error = e.message?.let { m -> UiText.Raw(m) } ?: UiText.Res(R.string.aichat_error_generic), messages = it.messages.filterNot { m -> m.text.isEmpty() }) }
            } finally {
                // a stopped stream must not reset the state of an already started next request
                if (gen == generation) _uiState.update { it.copy(streaming = false, toolStatus = null) }
            }
        }
    }
}

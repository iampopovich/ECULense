package io.github.iampopovich.eculense.feature.aichat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.ai.ChatEvent
import io.github.iampopovich.eculense.core.ai.ChatPrefs
import io.github.iampopovich.eculense.core.ai.ExpertNote
import io.github.iampopovich.eculense.core.ai.ProviderInfo
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

/** Вопрос ждёт согласия: какие данные и какому провайдеру уйдут. */
internal data class ConsentRequest(val provider: ProviderInfo, val text: String)

/** «Над ответом работали: …» и раскрываемые «Заметки экспертов». */
internal data class ExpertsInfo(val roles: List<String>, val notes: List<ExpertNote>)

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
    /** Чат идёт через модель на телефоне — честная пометка о качестве и скорости. */
    val onDevice: Boolean = false,
    /** Ждём согласия на отправку данных машины этому провайдеру. */
    val consent: ConsentRequest? = null,
    /** Текст, который надо вернуть в поле ввода (диалог согласия закрыли). */
    val draft: String? = null,
    /**
     * Кто работал над ответом — по индексу сообщения ответа. Только для текущего показа: в историю (ai_chat.db) пишется
     * лишь финальный ответ, после перезапуска подписи и заметок нет.
     */
    val experts: Map<Int, ExpertsInfo> = emptyMap(),
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
            // Диалог переживает перезапуск: id — в настройках, тексты — в ai_chat.db (ChatMemory).
            conversationId = prefs.conversationId() ?: conversationId.also { prefs.setConversationId(it) }
            val history = runCatching { gateway.history(conversationId) }.getOrDefault(emptyList())
            val onDevice = runCatching { gateway.provider()?.onDevice == true }.getOrDefault(false)
            _uiState.update { it.copy(useCarData = (saved ?: true) && it.carDataAvailable, messages = history + it.messages, onDevice = onDevice) }
            if (!prefill.isNullOrBlank()) send(prefill)
        }
    }

    /** «Новый диалог»: текущая переписка удаляется, ИИ её больше не видит. */
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
     * Перед первой отправкой данных машины облачному провайдеру — диалог согласия (что уходит и куда);
     * без согласия вопрос не уходит.
     */
    fun send(text: String) {
        if (text.isBlank() || _uiState.value.streaming || _uiState.value.consent != null) return
        val question = text.trim()
        viewModelScope.launch {
            if (_uiState.value.useCarData) {
                val provider = gateway.provider()
                // модель на телефоне: данные не покидают устройство — согласие не нужно
                if (provider != null && !provider.onDevice && !prefs.hasConsent(provider.key)) {
                    _uiState.update { it.copy(consent = ConsentRequest(provider, question)) }
                    return@launch
                }
            }
            ask(_uiState.value.messages + ChatMessage(ChatMessage.Role.USER, question), question)
        }
    }

    /** «Разрешить»: запоминаем согласие для этого провайдера и отправляем вопрос с данными машины. */
    fun acceptConsent() {
        val request = _uiState.value.consent ?: return
        viewModelScope.launch {
            prefs.grantConsent(request.provider.key)
            _uiState.update { it.copy(consent = null) }
            ask(_uiState.value.messages + ChatMessage(ChatMessage.Role.USER, request.text), request.text)
        }
    }

    /** «Без данных машины»: переключатель выключается, вопрос уходит без инструментов. */
    fun declineConsent() {
        val request = _uiState.value.consent ?: return
        _uiState.update { it.copy(consent = null, useCarData = false) }
        viewModelScope.launch { prefs.setUseCarData(false) }
        ask(_uiState.value.messages + ChatMessage(ChatMessage.Role.USER, request.text), request.text)
    }

    /** Закрыли диалог: ничего не отправляем, текст возвращается в поле ввода. */
    fun cancelConsent() {
        val request = _uiState.value.consent ?: return
        _uiState.update { it.copy(consent = null, draft = request.text) }
    }

    fun draftConsumed() {
        _uiState.update { it.copy(draft = null) }
    }

    /** Повтор вопроса после ошибки — без повторного ввода (неудачный ход агент в историю не записал). */
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
                            is ChatEvent.Status -> s.copy(toolStatus = event.label)
                            is ChatEvent.Experts -> s.copy(experts = s.experts + (s.messages.lastIndex to ExpertsInfo(event.roles, event.notes)))
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

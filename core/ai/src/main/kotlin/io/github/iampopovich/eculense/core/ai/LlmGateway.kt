package io.github.iampopovich.eculense.core.ai

import kotlinx.coroutines.flow.Flow

data class ChatMessage(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }
}

/** Событие ответа агента: кусок текста или «ИИ смотрит данные…» перед вызовом инструмента. */
sealed interface ChatEvent {
    data class Text(val delta: String) : ChatEvent
    data class ToolCall(val toolName: String, val label: String) : ChatEvent
}

/** Кому уходят данные — для диалога согласия (без API-ключа). */
/** key — для согласия (провайдер); title и model — показать пользователю, куда уйдут данные. */
data class ProviderInfo(val key: String, val title: String, val model: String)

interface LlmGateway {
    /** Ответ модели кусками по мере генерации. Ошибки — [LlmException] с понятным текстом. */
    fun stream(system: String, history: List<ChatMessage>): Flow<String>

    /** Подключены ли инструменты данных машины (модуль :core:aitools). */
    val hasVehicleTools: Boolean get() = false

    /**
     * Ход диалога через агента Koog: история — по [conversationId] (ChatMemory), ответ стримится,
     * перед вызовом инструмента приходит [ChatEvent.ToolCall]. useCarData = false — без инструментов.
     */
    fun chat(conversationId: String, userText: String, useCarData: Boolean): Flow<ChatEvent>

    /** Сохранённый диалог (тексты вопросов и ответов) — переживает перезапуск приложения. */
    suspend fun history(conversationId: String): List<ChatMessage> = emptyList()

    /** «Новый диалог»: старый удаляется (списка диалогов в приложении нет). */
    suspend fun deleteConversation(conversationId: String) = Unit

    /** Текущий провайдер; null — ИИ не настроен. */
    suspend fun provider(): ProviderInfo? = null
}

/** Ошибка ИИ с текстом для пользователя (без ключей и сырых ответов сервера). */
class LlmException(message: String, val statusCode: Int? = null, cause: Throwable? = null) : Exception(message, cause)

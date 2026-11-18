package io.github.iampopovich.eculense.core.ai

import kotlinx.coroutines.flow.Flow

data class ChatMessage(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }
}

interface LlmGateway {
    /** Ответ модели кусками по мере генерации. Ошибки — [LlmException] с понятным текстом. */
    fun stream(system: String, history: List<ChatMessage>): Flow<String>
}

/** Ошибка ИИ с текстом для пользователя (без ключей и сырых ответов сервера). */
class LlmException(message: String, val statusCode: Int? = null, cause: Throwable? = null) : Exception(message, cause)

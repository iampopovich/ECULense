package io.github.iampopovich.eculense.core.ai

import kotlinx.coroutines.flow.Flow

data class ChatMessage(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }
}

/** Agent response event: a chunk of text or «ИИ смотрит данные…» before a tool call. */
sealed interface ChatEvent {
    data class Text(val delta: String) : ChatEvent
    /** [roleId] — the expert role that called the tool (null: the plain agent); the UI builds its label from it. */
    data class ToolCall(val toolName: String, val label: String, val roleId: String? = null) : ChatEvent

    /** Waiting label without a tool: the on-phone model answers all at once, without streaming («Модель думает…»). */
    data class Status(val label: String, val key: ChatStatus? = null) : ChatEvent

    /**
     * Who worked on the answer: role names (lead first) and expert notes (empty if there is a single role that answered itself
     * or the on-phone model was "all roles at once"). Notes are not written to the dialog history — only the final answer.
     */
    data class Experts(val roles: List<String>, val notes: List<ExpertNote>, val roleIds: List<String> = emptyList()) : ChatEvent
}

/** What a [ChatEvent.Status] means, so the UI can show it in the app language; [ChatEvent.Status.label] is the legacy text. */
sealed interface ChatStatus {
    /** The on-phone model answers all at once. */
    data object PhoneThinking : ChatStatus

    /** The app builds the car data digest for the on-phone model. */
    data object CollectingData : ChatStatus

    /** The editor merges the expert notes into the answer. */
    data object Editor : ChatStatus

    /** An expert role is working on the question over [periodDays]. */
    data class Role(val roleId: String, val periodDays: Int) : ChatStatus
}

/**
 * Where the data goes — for the consent dialog (without the API key).
 * key — for consent (provider); title and model — shown to the user to say where the data goes.
 * [onDevice] — on-phone model: data goes nowhere, no consent needed.
 */
data class ProviderInfo(val key: String, val title: String, val model: String, val onDevice: Boolean = false)

interface LlmGateway {
    /** Model response in chunks as it is generated. Errors are [LlmException] with a readable message. */
    fun stream(system: String, history: List<ChatMessage>): Flow<String>

    /** Whether car data tools are wired in (module :core:aitools). */
    val hasVehicleTools: Boolean get() = false

    /**
     * Dialog turn via the Koog agent: history is keyed by [conversationId] (ChatMemory), the answer is streamed,
     * [ChatEvent.ToolCall] arrives before a tool call. useCarData = false — no tools.
     */
    fun chat(conversationId: String, userText: String, useCarData: Boolean): Flow<ChatEvent>

    /** Saved dialog (question and answer texts) — survives an app restart. */
    suspend fun history(conversationId: String): List<ChatMessage> = emptyList()

    /** «Новый диалог»: the old one is deleted (the app has no dialog list). */
    suspend fun deleteConversation(conversationId: String) = Unit

    /** Current provider; null — AI is not configured. */
    suspend fun provider(): ProviderInfo? = null
}

/** AI error with a user-facing message (no keys or raw server responses). */
class LlmException(message: String, val statusCode: Int? = null, cause: Throwable? = null) : Exception(message, cause)

package io.github.iampopovich.eculense.core.ai

import kotlinx.coroutines.flow.Flow

data class ChatMessage(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }
}

/**
 * Agent response event: a chunk of text, a tool call, a waiting status or who worked on the answer. Events carry ids
 * only (tool name, role id, [ChatStatus]); the UI turns them into text in the app language.
 */
sealed interface ChatEvent {
    data class Text(val delta: String) : ChatEvent

    /** [roleId] — the expert role that called the tool (null: the plain agent). */
    data class ToolCall(val toolName: String, val roleId: String? = null) : ChatEvent

    /** Waiting status without a tool (the on-phone model answers all at once, the editor is merging notes…). */
    data class Status(val key: ChatStatus) : ChatEvent

    /**
     * Who worked on the answer: role ids (lead first) and expert notes (empty if there is a single role that answered itself
     * or the on-phone model was "all roles at once"). Notes are not written to the dialog history — only the final answer.
     */
    data class Experts(val roleIds: List<String>, val notes: List<ExpertNote>) : ChatEvent
}

/** What a [ChatEvent.Status] means; the UI shows it in the app language. */
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
    /** Model response in chunks as it is generated. Errors are [LlmException] with a typed [LlmProblem]. */
    fun stream(system: String, history: List<ChatMessage>): Flow<String>

    /** Whether car data tools are wired in (module :core:aitools). */
    val hasVehicleTools: Boolean get() = false

    /**
     * Dialog turn via the Koog agent: history is keyed by [conversationId] (ChatMemory), the answer is streamed,
     * [ChatEvent.ToolCall] arrives before a tool call. useCarData = false — no tools. The answer language is the app
     * language at the moment of the call.
     */
    fun chat(conversationId: String, userText: String, useCarData: Boolean): Flow<ChatEvent>

    /** Saved dialog (question and answer texts) — survives an app restart. */
    suspend fun history(conversationId: String): List<ChatMessage> = emptyList()

    /** «New dialog»: the old one is deleted (the app has no dialog list). */
    suspend fun deleteConversation(conversationId: String) = Unit

    /** Current provider; null — AI is not configured. */
    suspend fun provider(): ProviderInfo? = null
}

/** Why an AI request failed; the UI maps each case to a text in the app language. */
sealed interface LlmProblem {
    /** No provider/model saved. */
    data object NotConfigured : LlmProblem

    /** The provider needs an API key, none is saved. */
    data object KeyMissing : LlmProblem

    /** «Other» provider without a server address. */
    data object UrlMissing : LlmProblem

    /** The saved «Other» address is invalid (e.g. saved before the check existed). */
    data class InvalidUrl(val problem: UrlProblem) : LlmProblem

    /** HTTP 401: wrong or revoked key. */
    data object Unauthorized : LlmProblem

    /** HTTP 403: no rights for the key or the provider blocks this network/region. */
    data object Forbidden : LlmProblem

    /** HTTP 402: no funds on the provider account. */
    data object PaymentRequired : LlmProblem

    /** HTTP 404: model or address not found. */
    data object NotFound : LlmProblem

    /** HTTP 429: too many requests. */
    data object RateLimited : LlmProblem

    /** HTTP 5xx. */
    data class ServerDown(val status: Int) : LlmProblem

    /** Any other HTTP status. */
    data class Rejected(val status: Int) : LlmProblem

    /** No connection to the AI server. */
    data object Network : LlmProblem

    /** The on-phone model failed to load; [detail] is a technical reason. */
    data class OnDeviceLoad(val detail: String) : LlmProblem

    /** The on-phone model failed while answering; [detail] is a technical reason. */
    data class OnDeviceFailed(val detail: String) : LlmProblem

    /** Anything else; [detail] is a technical message (exception text or type), shown raw. */
    data class Other(val detail: String) : LlmProblem
}

/**
 * AI error: a typed [problem] for the UI, [serverDetail] — the provider's own reason (raw, already stripped of the key).
 * The exception message is an English technical text for logs, never shown as is.
 */
class LlmException(
    val problem: LlmProblem,
    val serverDetail: String? = null,
    cause: Throwable? = null,
) : Exception(technical(problem, serverDetail), cause) {
    val statusCode: Int?
        get() = when (problem) {
            LlmProblem.Unauthorized -> 401
            LlmProblem.Forbidden -> 403
            LlmProblem.PaymentRequired -> 402
            LlmProblem.NotFound -> 404
            LlmProblem.RateLimited -> 429
            is LlmProblem.ServerDown -> problem.status
            is LlmProblem.Rejected -> problem.status
            else -> null
        }

    private companion object {
        fun technical(problem: LlmProblem, detail: String?): String =
            "AI request failed: $problem" + (detail?.let { " (server: $it)" } ?: "")
    }
}

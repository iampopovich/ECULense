package io.github.iampopovich.eculense.core.ai

import ai.koog.agents.chatMemory.feature.ChatMemoryPreProcessor
import ai.koog.agents.core.agent.AIAgentFunctionalStrategy
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame

/**
 * Koog agent strategy for chat: streamed response + tool calls between rounds.
 *
 * Koog 1.3.0 built-in strategies (singleRunStrategy, reAct) call the model without streaming, so the answer would arrive
 * whole after all tools. Hence a custom loop on [functionalStrategy]: `requestLLMStreaming()` → text chunks go straight
 * to the UI → frames are assembled into [Message.Assistant] → if it contains tool calls, `executeTools` (via the agent
 * environment, with pipeline events) → results go into the prompt → next round.
 */
internal object VehicleChatStrategy {
    /** Maximum number of rounds with tools; the last round has no tools so the model answers with text. */
    const val MAX_TOOL_ROUNDS = 5

    const val THINKING = "Модель на телефоне думает…"

    fun create(
        system: String,
        emit: suspend (ChatEvent) -> Unit,
        label: (String) -> String,
        streaming: Boolean = true,
        thinkingLabel: String = THINKING,
        maxToolRounds: Int = MAX_TOOL_ROUNDS,
    ): AIAgentFunctionalStrategy<String, String> = functionalStrategy("ecu-chat") { input ->
        llm.writeSession {
            // ChatMemory injects the saved history without the system message, so set the current one again
            // (it depends on the «данные машины» toggle).
            prompt = prompt.withMessages { messages ->
                listOf(Message.System(system, RequestMetaInfo.Empty)) + messages.filterNot { it is Message.System }
            }
            appendPrompt { user(input) }
        }
        val answer = StringBuilder()
        for (round in 0..maxToolRounds) {
            val response = llm.writeSession {
                if (round == maxToolRounds) tools = emptyList()
                if (streaming) {
                    val frames = mutableListOf<StreamFrame>()
                    requestLLMStreaming().collect { frame ->
                        frames += frame
                        if (frame is StreamFrame.TextDelta && frame.text.isNotEmpty()) emit(ChatEvent.Text(frame.text))
                    }
                    assistantOf(frames).also { appendPrompt { message(it) } }
                } else {
                    // The on-device model (Koog LiteRT client) does not stream: the whole answer at once, «Модель думает…» until then.
                    emit(ChatEvent.Status(thinkingLabel))
                    val whole = requestLLM() // the write session adds the response to the prompt itself
                    val text = whole.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
                    if (text.isNotEmpty()) emit(ChatEvent.Text(text))
                    whole
                }
            }
            val text = response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
            answer.append(text)
            val calls = response.parts.filterIsInstance<MessagePart.Tool.Call>()
            if (calls.isEmpty()) break
            // separate text between rounds with a paragraph so the answer doesn't run together
            if (text.isNotBlank()) emit(ChatEvent.Text("\n\n"))
            calls.forEach { emit(ChatEvent.ToolCall(it.tool, label(it.tool))) }
            val results = executeTools(calls)
            llm.writeSession { appendPrompt { user { results.forEach { toolResult(it.toMessagePart()) } } } }
        }
        answer.toString()
    }

    /**
     * Frames → assistant message. Like Koog's `toMessageResponse()`, but empty call arguments → "{}"
     * (some OpenAI-compatible servers send "" for tools without parameters), and text without TextComplete
     * is assembled from deltas.
     */
    fun assistantOf(frames: List<StreamFrame>): Message.Assistant {
        val end = frames.filterIsInstance<StreamFrame.End>().lastOrNull()
        val parts = mutableListOf<MessagePart.ResponsePart>()
        frames.filterIsInstance<StreamFrame.ReasoningComplete>().forEach {
            parts += MessagePart.Reasoning(id = it.id, content = it.content, summary = it.summary, encrypted = it.encrypted)
        }
        val complete = frames.filterIsInstance<StreamFrame.TextComplete>().joinToString("") { it.text }
        val text = complete.ifEmpty { frames.filterIsInstance<StreamFrame.TextDelta>().joinToString("") { it.text } }
        if (text.isNotEmpty()) parts += MessagePart.Text(text)
        frames.filterIsInstance<StreamFrame.ToolCallComplete>().forEach {
            parts += MessagePart.Tool.Call(id = it.id, tool = it.name, args = it.content.ifBlank { "{}" })
        }
        return Message.Assistant(parts, end?.metaInfo ?: ResponseMetaInfo.Empty, end?.finishReason)
    }
}

/**
 * What ChatMemory saves: only question and answer texts (the window is in [ChatHistoryStore.load]). Tool calls and their
 * JSON are not needed in later turns (the model will call the tool again) and cost tokens; besides, cutting the window
 * in the middle of a call/result pair breaks the API request.
 */
internal object TextOnlyHistory : ChatMemoryPreProcessor {

    override fun preprocess(messages: List<Message>): List<Message> = messages.mapNotNull { m ->
        val text = m.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
        when {
            text.isBlank() -> null
            m is Message.User -> Message.User(text, m.metaInfo)
            m is Message.Assistant -> Message.Assistant(text, m.metaInfo)
            else -> null
        }
    }
}

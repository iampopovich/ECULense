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
 * Стратегия агента Koog для чата: стриминг ответа + вызовы инструментов между кругами.
 *
 * Готовые стратегии Koog 1.3.0 (singleRunStrategy, reAct) зовут модель без стриминга — ответ пришёл бы целиком
 * после всех инструментов. Поэтому свой цикл на [functionalStrategy]: `requestLLMStreaming()` → куски текста сразу
 * в UI → из кадров собирается [Message.Assistant] → если в нём вызовы инструментов, `executeTools` (через окружение
 * агента, с событиями пайплайна) → результаты в промпт → следующий круг.
 */
internal object VehicleChatStrategy {
    /** Сколько кругов с инструментами максимум; последний круг — без инструментов, чтобы модель ответила текстом. */
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
            // ChatMemory подставляет сохранённую историю без системного сообщения — задаём актуальное заново
            // (оно зависит от переключателя «данные машины»).
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
                    // Модель на телефоне (клиент Koog LiteRT) не стримит: ответ целиком, до него — «Модель думает…».
                    emit(ChatEvent.Status(thinkingLabel))
                    val whole = requestLLM() // write-сессия сама добавляет ответ в промпт
                    val text = whole.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
                    if (text.isNotEmpty()) emit(ChatEvent.Text(text))
                    whole
                }
            }
            val text = response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
            answer.append(text)
            val calls = response.parts.filterIsInstance<MessagePart.Tool.Call>()
            if (calls.isEmpty()) break
            // текст между кругами отделяем абзацем, чтобы ответ не склеивался
            if (text.isNotBlank()) emit(ChatEvent.Text("\n\n"))
            calls.forEach { emit(ChatEvent.ToolCall(it.tool, label(it.tool))) }
            val results = executeTools(calls)
            llm.writeSession { appendPrompt { user { results.forEach { toolResult(it.toMessagePart()) } } } }
        }
        answer.toString()
    }

    /**
     * Кадры → сообщение ассистента. Как `toMessageResponse()` Koog, но пустые аргументы вызова → "{}"
     * (некоторые OpenAI-совместимые серверы шлют "" для инструментов без параметров), а текст без TextComplete
     * собирается из дельт.
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
 * Что сохраняет ChatMemory: только тексты вопросов и ответов (окно — в [ChatHistoryStore.load]). Вызовы инструментов и их
 * JSON в следующих ходах не нужны (модель вызовет инструмент заново) и стоят токенов; к тому же обрезка окна
 * посреди пары «вызов — результат» ломает запрос к API.
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

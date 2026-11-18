package io.github.iampopovich.eculense.core.ai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.serialization.typeToken
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress

/** Агент чата: стриминг + инструменты + ChatMemory. Сеть не нужна: поддельный исполнитель или локальный SSE-сервер. */
class VehicleChatAgentTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: HttpServer? = null
    private val plain = object : SecretCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(encoded: String) = encoded
    }

    @After fun tearDown() {
        server?.stop(0)
        scope.cancel()
    }

    private suspend fun store(provider: LlmProviderKind = LlmProviderKind.DEEPSEEK, model: String = "deepseek-chat"): LlmSettingsStore {
        val ds = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s${System.nanoTime()}.preferences_pb") }
        return LlmSettingsStore(ds, plain).also { it.save(provider, model, null, "k") }
    }

    @Serializable
    data class DtcArgs(@property:LLMDescription("max codes") val limit: Int = 30)

    /** Инструмент как в :core:aitools — типизированный SimpleTool с @Serializable аргументами. */
    class FakeDtcTool(val calls: MutableList<Int>) : SimpleTool<DtcArgs>(typeToken<DtcArgs>(), "dtcHistory", "DTC history") {
        override suspend fun execute(args: DtcArgs): String {
            calls += args.limit
            delay(1) // suspend внутри инструмента
            return """{"codes":[{"code":"P0171","events":2}]}"""
        }
    }

    private class Toolbox(val calls: MutableList<Int> = mutableListOf()) : VehicleToolbox {
        override fun registry() = ToolRegistry { tool(FakeDtcTool(calls)) }
        override fun progressLabel(toolName: String) = if (toolName == "dtcHistory") "ИИ смотрит ошибки…" else "ИИ смотрит данные…"
    }

    /** Скрипт ответов: каждый вызов модели отдаёт следующий набор кадров и запоминает промпт и инструменты. */
    private class ScriptedExecutor(private val script: List<List<StreamFrame>>) : PromptExecutor() {
        val prompts = mutableListOf<Prompt>()
        val toolNames = mutableListOf<List<String>>()
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant = error("not used")
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
            prompts += prompt
            toolNames += tools.map { it.name }
            return flowOf(*script[(prompts.size - 1).coerceAtMost(script.lastIndex)].toTypedArray())
        }
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")
        override fun close() = Unit
    }

    private val toolRound = listOf(StreamFrame.ToolCallComplete("call_1", "dtcHistory", """{"limit":5}"""), StreamFrame.End("tool_calls"))
    private fun textRound(vararg chunks: String) =
        chunks.map { StreamFrame.TextDelta(it) } + StreamFrame.TextComplete(chunks.joinToString("")) + StreamFrame.End("stop")

    @Test
    fun `tool call shows progress, runs the tool and the answer streams`() = runBlocking {
        val exec = ScriptedExecutor(listOf(toolRound, textRound("Код ", "P0171 — бедная смесь")))
        val toolbox = Toolbox()
        val gateway = KoogLlmGateway(store(), toolbox = toolbox) { _, _ -> exec }

        val events = gateway.chat("c1", "Объясни мои ошибки", useCarData = true).toList()

        assertEquals(
            listOf(ChatEvent.ToolCall("dtcHistory", "ИИ смотрит ошибки…"), ChatEvent.Text("Код "), ChatEvent.Text("P0171 — бедная смесь")),
            events,
        )
        assertEquals(listOf(5), toolbox.calls)
        assertEquals(listOf("dtcHistory"), exec.toolNames.first())
        // второй запрос несёт результат инструмента; системный промпт — с правилами про инструменты
        val second = exec.prompts[1].messages
        assertTrue(second.first().textContent().contains("Все числа, даты и коды бери только из ответов инструментов"))
        val result = second.filterIsInstance<Message.User>().flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>().single()
        assertTrue(result.parts.joinToString { (it as? MessagePart.Text)?.text.orEmpty() }.contains("P0171"))
    }

    @Test
    fun `chat memory keeps only texts of the same conversation`() = runBlocking {
        val exec = ScriptedExecutor(listOf(toolRound, textRound("Первый ответ"), textRound("Второй ответ")))
        val gateway = KoogLlmGateway(store(), toolbox = Toolbox()) { _, _ -> exec }

        gateway.chat("c1", "Вопрос 1", useCarData = true).toList()
        gateway.chat("c1", "Вопрос 2", useCarData = true).toList()

        val third = exec.prompts[2].messages
        assertEquals(listOf("Вопрос 1", "Первый ответ", "Вопрос 2"), third.drop(1).map { it.textContent() })
        assertTrue(third.first() is Message.System)
        assertTrue(third.none { m -> m.parts.any { it is MessagePart.Tool.Call || it is MessagePart.Tool.Result } })

        gateway.chat("c2", "Новый диалог", useCarData = true).toList()
        assertEquals(listOf("Новый диалог"), exec.prompts[3].messages.drop(1).map { it.textContent() })
        // для экрана — полный текст диалога; новый диалог удаляет старый
        assertEquals(
            listOf(ChatMessage(ChatMessage.Role.USER, "Вопрос 1"), ChatMessage(ChatMessage.Role.ASSISTANT, "Первый ответ"), ChatMessage(ChatMessage.Role.USER, "Вопрос 2"), ChatMessage(ChatMessage.Role.ASSISTANT, "Второй ответ")),
            gateway.history("c1"),
        )
        gateway.deleteConversation("c1")
        assertEquals(emptyList<ChatMessage>(), gateway.history("c1"))
    }

    @Test
    fun `without car data no tools are offered and the prompt has no tool rules`() = runBlocking {
        val exec = ScriptedExecutor(listOf(textRound("Без данных")))
        val gateway = KoogLlmGateway(store(), toolbox = Toolbox()) { _, _ -> exec }
        val events = gateway.chat("c1", "Что такое P0300?", useCarData = false).toList()
        assertEquals(listOf(ChatEvent.Text("Без данных")), events)
        assertEquals(emptyList<String>(), exec.toolNames.single())
        assertFalse(exec.prompts.single().messages.first().textContent().contains("инструмент"))
        assertFalse(KoogLlmGateway(store()) { _, _ -> exec }.hasVehicleTools)
    }

    @Test
    fun `endless tool calls stop after the limit with a last round without tools`() = runBlocking {
        val exec = ScriptedExecutor(List(VehicleChatStrategy.MAX_TOOL_ROUNDS) { toolRound } + listOf(textRound("Итог")))
        val gateway = KoogLlmGateway(store(), toolbox = Toolbox()) { _, _ -> exec }
        val events = gateway.chat("c1", "?", useCarData = true).toList()
        assertEquals(ChatEvent.Text("Итог"), events.last())
        assertEquals(emptyList<String>(), exec.toolNames.last())
    }

    @Test
    fun `empty tool arguments are accepted`() {
        val m = VehicleChatStrategy.assistantOf(listOf(StreamFrame.TextDelta("a"), StreamFrame.ToolCallComplete("1", "carProfile", ""), StreamFrame.End()))
        assertEquals("{}", m.parts.filterIsInstance<MessagePart.Tool.Call>().single().args)
        assertEquals("a", m.textContent())
    }

    @Test
    fun `history preprocessor strips tool traffic and system`() {
        val meta = ai.koog.prompt.message.RequestMetaInfo.Empty
        val rmeta = ai.koog.prompt.message.ResponseMetaInfo.Empty
        val msgs = listOf(
            Message.System("sys", meta),
            Message.User("q", meta),
            Message.Assistant(listOf(MessagePart.Text("смотрю"), MessagePart.Tool.Call("1", "dtcHistory", "{}")), rmeta),
            Message.User(MessagePart.Tool.Result("1", "dtcHistory", listOf(MessagePart.Text("{}"))), meta),
            Message.Assistant("ответ", rmeta),
        )
        assertEquals(listOf("q", "смотрю", "ответ"), TextOnlyHistory.preprocess(msgs).map { it.textContent() })
    }

    // --- Модель на телефоне (:core:ailocal): без стриминга, свой исполнитель, облачные настройки не нужны.
    private class WholeAnswerExecutor(private val script: List<Message.Assistant>) : PromptExecutor() {
        val prompts = mutableListOf<Prompt>()
        val toolNames = mutableListOf<List<String>>()
        var closed = false
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            prompts += prompt
            toolNames += tools.map { it.name }
            return script[(prompts.size - 1).coerceAtMost(script.lastIndex)]
        }
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = error("модель на телефоне не стримит")
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")
        override fun close() {
            closed = true
        }
    }

    private class FakeOnDevice(val session: OnDeviceSession?) : OnDeviceLlm {
        override suspend fun session() = session
        override val summary = kotlinx.coroutines.flow.flowOf<OnDeviceSummary?>(null)
        override suspend fun deactivate() = Unit
    }

    private val localInfo = ProviderInfo("on-device", "На устройстве", "Тестовая модель", onDevice = true)
    private val localModel = LLModel(ai.koog.prompt.llm.LLMProvider.OpenAI, "test.litertlm", listOf(ai.koog.prompt.llm.LLMCapability.Tools, ai.koog.prompt.llm.LLMCapability.Completion), contextLength = 4096)
    private val rmeta = ai.koog.prompt.message.ResponseMetaInfo.Empty

    @Test
    fun `on-device model answers whole with a thinking status, runs tools and is not closed`() = runBlocking {
        val exec = WholeAnswerExecutor(
            listOf(
                Message.Assistant(listOf(MessagePart.Tool.Call("1", "dtcHistory", """{"limit":2}""")), rmeta),
                Message.Assistant("P0171 — бедная смесь", rmeta),
            ),
        )
        val toolbox = Toolbox()
        // облачный провайдер тоже сохранён — но выбран локальный, облако не вызывается
        val gateway = KoogLlmGateway(store(), toolbox = toolbox, onDevice = FakeOnDevice(OnDeviceSession(localInfo, localModel, exec, streaming = false, supportsTools = true))) { _, _ ->
            error("облачный исполнитель не должен создаваться")
        }
        val events = gateway.chat("c1", "Объясни мои ошибки", useCarData = true).toList()
        assertEquals(
            listOf(
                ChatEvent.Status(VehicleChatStrategy.THINKING),
                ChatEvent.ToolCall("dtcHistory", "ИИ смотрит ошибки…"),
                ChatEvent.Status(VehicleChatStrategy.THINKING),
                ChatEvent.Text("P0171 — бедная смесь"),
            ),
            events,
        )
        assertEquals(listOf(2), toolbox.calls)
        assertEquals(listOf("dtcHistory"), exec.toolNames.first())
        assertFalse("движок модели держится между вопросами", exec.closed)
        assertEquals(localInfo, gateway.provider())
        assertEquals("P0171 — бедная смесь", gateway.history("c1").last().text)
    }

    @Test
    fun `on-device model without tool support gets no tools even with car data on`() = runBlocking {
        val exec = WholeAnswerExecutor(listOf(Message.Assistant("Ответ", rmeta)))
        val gateway = KoogLlmGateway(store(), toolbox = Toolbox(), onDevice = FakeOnDevice(OnDeviceSession(localInfo, localModel, exec, streaming = false, supportsTools = false)))
        gateway.chat("c1", "?", useCarData = true).toList()
        assertEquals(emptyList<String>(), exec.toolNames.single())
    }

    @Test
    fun `without an on-device selection the cloud provider is used`() = runBlocking {
        val exec = ScriptedExecutor(listOf(textRound("Облако")))
        val gateway = KoogLlmGateway(store(), onDevice = FakeOnDevice(null)) { _, _ -> exec }
        assertEquals(listOf(ChatEvent.Text("Облако")), gateway.chat("c1", "?", useCarData = false).toList())
        assertEquals("DEEPSEEK", gateway.provider()?.key)
    }

    // --- Spike (план 3.4): Koog 1.3.0 поддерживает suspend-методы @Tool в ToolSet (ToolFromCallable → callSuspendBy).
    @LLMDescription("spike")
    class SpikeSet : ToolSet {
        @Tool
        @LLMDescription("suspend tool")
        suspend fun ping(@LLMDescription("n") n: Int): String {
            delay(1)
            return "pong $n"
        }
    }

    @Test
    fun `spike - suspend Tool methods in a ToolSet work in Koog 1_3_0`() = runBlocking {
        val tool = SpikeSet().asTools().single()
        assertEquals("ping", tool.name)
        val registry = ToolRegistry { tools(SpikeSet()) }
        assertEquals(listOf("ping"), registry.tools.map { it.name })
    }

    // --- Настоящий клиент DeepSeek (OpenAI-совместимый SSE) с вызовом инструмента.
    private fun sse(vararg data: String) = data.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    @Test
    fun `real DeepSeek client streams a tool call and then the answer`() = runBlocking {
        val bodies = mutableListOf<String>()
        val replies = ArrayDeque(
            listOf(
                sse(
                    """{"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-chat","system_fingerprint":"fp_1","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_0","type":"function","function":{"name":"dtcHistory","arguments":""}}]},"finish_reason":null}]}""",
                    """{"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-chat","system_fingerprint":"fp_1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"limit\":3}"}}]},"finish_reason":null}]}""",
                    """{"id":"1","object":"chat.completion.chunk","created":1,"model":"deepseek-chat","system_fingerprint":"fp_1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
                ),
                sse(
                    """{"id":"2","object":"chat.completion.chunk","created":1,"model":"deepseek-chat","system_fingerprint":"fp_1","choices":[{"index":0,"delta":{"role":"assistant","content":"P0171 "},"finish_reason":null}]}""",
                    """{"id":"2","object":"chat.completion.chunk","created":1,"model":"deepseek-chat","system_fingerprint":"fp_1","choices":[{"index":0,"delta":{"content":"дважды"},"finish_reason":null}]}""",
                    """{"id":"2","object":"chat.completion.chunk","created":1,"model":"deepseek-chat","system_fingerprint":"fp_1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                ),
            ),
        )
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { ex ->
            bodies += ex.requestBody.readBytes().decodeToString()
            val bytes = replies.removeFirst().toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        val root = "http://127.0.0.1:${http.address.port}"
        val toolbox = Toolbox()
        val gateway = KoogLlmGateway(store(), toolbox = toolbox) { s, key -> KoogClients.executor(s, key, presetBaseUrlForTests = root) }

        // DeepSeekLLMClient 1.3.0 требует system_fingerprint в кадрах; без него ошибка разбора маскируется Ktor как «Content-Length mismatch».
        val events = gateway.chat("c1", "Объясни мои ошибки", useCarData = true).toList()

        assertEquals(ChatEvent.ToolCall("dtcHistory", "ИИ смотрит ошибки…"), events.first())
        assertEquals("P0171 дважды", events.filterIsInstance<ChatEvent.Text>().joinToString("") { it.delta })
        assertEquals(listOf(3), toolbox.calls)
        assertTrue(bodies[0], bodies[0].contains("\"tools\"") && bodies[0].contains("dtcHistory"))
        // второй запрос содержит результат инструмента (role tool) и вызов (tool_calls)
        assertTrue(bodies[1], bodies[1].contains("\"tool_call_id\":\"call_0\"") && bodies[1].contains("P0171"))
    }
}

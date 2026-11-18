package io.github.iampopovich.eculense.core.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress

class KoogLlmGatewayTest {
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

    private suspend fun store(provider: LlmProviderKind, model: String, baseUrl: String?, key: String?): LlmSettingsStore {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "gw.preferences_pb") }
        return LlmSettingsStore(dataStore, plain).also { it.save(provider, model, baseUrl, key) }
    }

    /** Поддельный исполнитель Koog: отдаёт заданные кадры, ничего не ходит в сеть. */
    private class FakeExecutor(private val frames: List<StreamFrame>) : PromptExecutor() {
        var lastPrompt: Prompt? = null
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
            error("not used")
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
            lastPrompt = prompt
            return flowOf(*frames.toTypedArray())
        }
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("not used")
        override fun close() = Unit
    }

    @Test
    fun `stream passes only text deltas and sends history`() = runBlocking {
        val fake = FakeExecutor(listOf(StreamFrame.TextDelta("Про"), StreamFrame.ReasoningDelta(text = "думаю"), StreamFrame.TextDelta("верьте"), StreamFrame.End()))
        val gateway = KoogLlmGateway(store(LlmProviderKind.OPENROUTER, "m", null, "k")) { _, _ -> fake }
        val chunks = gateway.stream("sys", listOf(ChatMessage(ChatMessage.Role.USER, "P0133?"))).toList()
        assertEquals(listOf("Про", "верьте"), chunks)
        val messages = fake.lastPrompt!!.messages
        assertTrue(messages.first() is Message.System)
        assertEquals("P0133?", messages.last().textContent())
    }

    @Test
    fun `not configured gives readable error`() = runBlocking {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "empty.preferences_pb") }
        val gateway = KoogLlmGateway(LlmSettingsStore(dataStore, plain))
        try {
            gateway.stream("sys", listOf(ChatMessage(ChatMessage.Role.USER, "hi"))).toList()
            fail("expected error")
        } catch (e: LlmException) {
            assertEquals("ИИ не настроен: откройте Настройки → ИИ-помощник", e.message)
        }
    }

    private fun startServer(status: Int, contentType: String, body: String, seen: MutableList<String>): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            seen += "${exchange.requestMethod} ${exchange.requestURI.path} auth=${exchange.requestHeaders.getFirst("Authorization")}"
            exchange.requestBody.readBytes()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    private fun chunk(content: String?, finish: String? = null): String {
        val delta = if (content != null) """{"role":"assistant","content":"$content"}""" else "{}"
        val reason = finish?.let { "\"$it\"" } ?: "null"
        return """data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"openai/gpt-4.1-mini","choices":[{"index":0,"delta":$delta,"finish_reason":$reason}]}""" + "\n\n"
    }

    @Test
    fun `real OpenRouter client streams from an SSE server`() = runBlocking {
        val seen = mutableListOf<String>()
        val sse = chunk("Привет") + chunk(", мир") + chunk(null, "stop") + "data: [DONE]\n\n"
        val baseUrl = startServer(200, "text/event-stream", sse, seen)
        val gateway = KoogLlmGateway(store(LlmProviderKind.OPENROUTER, "openai/gpt-4.1-mini", baseUrl, "sk-or-test"))

        val answer = gateway.stream("sys", listOf(ChatMessage(ChatMessage.Role.USER, "hi"))).toList().joinToString("")

        assertEquals("Привет, мир", answer)
        assertEquals(listOf("POST /api/v1/chat/completions auth=Bearer sk-or-test"), seen)
    }

    @Test
    fun `401 from OpenRouter becomes a readable auth error without the key`() = runBlocking {
        val seen = mutableListOf<String>()
        val baseUrl = startServer(401, "application/json", """{"error":{"message":"No auth credentials found","code":401}}""", seen)
        val gateway = KoogLlmGateway(store(LlmProviderKind.OPENROUTER, "openai/gpt-4.1-mini", baseUrl, "sk-or-invalid"))
        try {
            gateway.stream("sys", listOf(ChatMessage(ChatMessage.Role.USER, "hi"))).toList()
            fail("expected error")
        } catch (e: LlmException) {
            assertEquals(401, e.statusCode)
            assertTrue(e.message, e.message!!.contains("401"))
            assertTrue(e.message, e.message!!.contains("No auth credentials found"))
            assertFalse(e.message, e.message!!.contains("sk-or-invalid"))
        }
        val ping = gateway.ping()
        assertTrue(ping.isFailure)
        assertTrue(ping.exceptionOrNull()!!.message!!.contains("401"))
    }

    @Test
    fun `unreachable server gives a network error`() = runBlocking {
        val gateway = KoogLlmGateway(store(LlmProviderKind.OPENROUTER, "m", "http://127.0.0.1:1", "k"))
        val ping = gateway.ping()
        assertTrue(ping.exceptionOrNull()?.message.orEmpty(), ping.exceptionOrNull()?.message.orEmpty().startsWith("Нет связи"))
    }
}

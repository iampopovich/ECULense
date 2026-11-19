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

    private var files = 0

    private suspend fun store(provider: LlmProviderKind, model: String, baseUrl: String?, key: String?): LlmSettingsStore {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "gw${files++}.preferences_pb") }
        return LlmSettingsStore(dataStore, plain).also { it.save(provider, model, baseUrl, key) }
    }

    /** Fake Koog executor: returns the given frames, never touches the network. */
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
        // Base URL as in Koog settings: the host root; the Koog client appends the paths.
        return "http://127.0.0.1:${http.address.port}"
    }

    private suspend fun streamError(gateway: KoogLlmGateway): LlmException = try {
        gateway.stream("sys", listOf(ChatMessage(ChatMessage.Role.USER, "hi"))).toList()
        throw AssertionError("expected error")
    } catch (e: LlmException) {
        e
    }

    private fun chunk(content: String?, finish: String? = null): String {
        val delta = if (content != null) """{"role":"assistant","content":"$content"}""" else "{}"
        val reason = finish?.let { "\"$it\"" } ?: "null"
        return """data: {"id":"gen-1","object":"chat.completion.chunk","created":1,"model":"openai/gpt-4.1-mini","system_fingerprint":"fp_1","choices":[{"index":0,"delta":$delta,"finish_reason":$reason}]}""" + "\n\n"
    }

    /** Preset with a real Koog client pointed at the local server via the KoogClients test entry point. */
    private fun preset(store: LlmSettingsStore, root: String) =
        KoogLlmGateway(store) { s, key -> KoogClients.executor(s, key, presetBaseUrlForTests = root) }

    @Test
    fun `real OpenRouter client streams from an SSE server`() = runBlocking {
        val seen = mutableListOf<String>()
        val sse = chunk("Привет") + chunk(", мир") + chunk(null, "stop") + "data: [DONE]\n\n"
        val baseUrl = startServer(200, "text/event-stream", sse, seen)
        val gateway = preset(store(LlmProviderKind.OPENROUTER, "openai/gpt-4.1-mini", null, "sk-or-test"), baseUrl)

        val answer = gateway.stream("sys", listOf(ChatMessage(ChatMessage.Role.USER, "hi"))).toList().joinToString("")

        assertEquals("Привет, мир", answer)
        assertEquals(listOf("POST /api/v1/chat/completions auth=Bearer sk-or-test"), seen)
    }

    @Test
    fun `401 from OpenRouter becomes a readable auth error without the key`() = runBlocking {
        val seen = mutableListOf<String>()
        val baseUrl = startServer(401, "application/json", """{"error":{"message":"No auth credentials found","code":401}}""", seen)
        val gateway = preset(store(LlmProviderKind.OPENROUTER, "openai/gpt-4.1-mini", null, "sk-or-invalid"), baseUrl)
        try {
            gateway.stream("sys", listOf(ChatMessage(ChatMessage.Role.USER, "hi"))).toList()
            fail("expected error")
        } catch (e: LlmException) {
            assertEquals(401, e.statusCode)
            assertTrue(e.message, e.message!!.contains("401"))
            assertTrue(e.message, e.message!!.contains("No auth credentials found"))
            assertFalse(e.message, e.message!!.contains("sk-or-invalid"))
        }
    }

    @Test
    fun `403 block is not reported as a bad key and shows the server reason`() = runBlocking {
        // This is how OpenRouter (Cloudflare) answers any request from a phone on a blocked network/region.
        val baseUrl = startServer(403, "application/json", """{ "success": false, "error": "Access denied by security policy." }""", mutableListOf())
        val gateway = preset(store(LlmProviderKind.OPENROUTER, "openai/gpt-4.1-mini", null, "sk-or-invalid"), baseUrl)
        val message = streamError(gateway).message!!
        assertTrue(message, message.contains("403"))
        assertTrue(message, message.contains("Access denied by security policy."))
        assertFalse(message, message.contains("неверный"))
    }

    @Test
    fun `unreachable server gives a network error`() = runBlocking {
        val gateway = preset(store(LlmProviderKind.OPENROUTER, "m", null, "k"), "http://127.0.0.1:1")
        val message = streamError(gateway).message.orEmpty()
        assertTrue(message, message.startsWith("Нет связи"))
    }

    @Test
    fun `Other endpoint passes the full saved base url to the OpenAI client, no key needed`() = runBlocking {
        val seen = mutableListOf<String>()
        val sse = chunk("# Ок") + chunk(null, "stop") + "data: [DONE]\n\n"
        val root = startServer(200, "text/event-stream", sse, seen)
        val gateway = KoogLlmGateway(store(LlmProviderKind.OTHER, "local-model", "$root/v1", null))

        val answer = gateway.stream("sys", listOf(ChatMessage(ChatMessage.Role.USER, "hi"))).toList().joinToString("")

        assertEquals("# Ок", answer)
        assertEquals(listOf("POST /v1/chat/completions auth=Bearer not-needed"), seen)
    }

    @Test
    fun `Other saved with a public http address is refused before any request`() = runBlocking {
        val gateway = KoogLlmGateway(store(LlmProviderKind.OTHER, "m", "http://example.com/v1", null))
        val message = streamError(gateway).message.orEmpty()
        assertTrue(message, message.contains("https://"))
    }

    @Test
    fun `presets stream through their own Koog clients with Koog paths`() = runBlocking {
        val sse = chunk("Да") + chunk(null, "stop") + "data: [DONE]\n\n"
        val expected = mapOf(
            LlmProviderKind.OPENAI to "POST /v1/chat/completions auth=Bearer k1",
            LlmProviderKind.DEEPSEEK to "POST /chat/completions auth=Bearer k1",
            LlmProviderKind.MISTRAL to "POST /v1/chat/completions auth=Bearer k1",
        )
        expected.forEach { (kind, request) ->
            val seen = mutableListOf<String>()
            val root = startServer(200, "text/event-stream", sse, seen)
            val gateway = preset(store(kind, "m", null, "k1"), root)
            val answer = gateway.stream("sys", listOf(ChatMessage(ChatMessage.Role.USER, "hi"))).toList().joinToString("")
            assertEquals(kind.name, "Да", answer)
            assertEquals(kind.name, listOf(request), seen)
            server?.stop(0)
        }
    }

    @Test
    fun `ollama streams ndjson through Koog's Ollama client without a key`() = runBlocking {
        val seen = mutableListOf<String>()
        val ndjson = listOf(
            """{"model":"llama3.2","message":{"role":"assistant","content":"## При"},"done":false}""",
            """{"model":"llama3.2","message":{"role":"assistant","content":"вет"},"done":false}""",
            """{"model":"llama3.2","message":{"role":"assistant","content":""},"done":true}""",
        ).joinToString("\n", postfix = "\n")
        val root = startServer(200, "application/x-ndjson", ndjson, seen)
        val gateway = preset(store(LlmProviderKind.OLLAMA, "llama3.2", null, null), root)

        val answer = gateway.stream("sys", listOf(ChatMessage(ChatMessage.Role.USER, "hi"))).toList().joinToString("")

        assertEquals("## Привет", answer)
        assertEquals(listOf("POST /api/chat auth=null"), seen)
    }

    @Test
    fun `saved provider without a model is not configured`() = runBlocking {
        val gateway = KoogLlmGateway(store(LlmProviderKind.OPENROUTER, "", null, "k"))
        assertEquals("ИИ не настроен: откройте Настройки → ИИ-помощник", streamError(gateway).message)
    }
}

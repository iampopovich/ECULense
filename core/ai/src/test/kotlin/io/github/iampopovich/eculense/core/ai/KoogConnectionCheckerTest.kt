package io.github.iampopovich.eculense.core.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * Проверка подключения идёт через настоящие клиенты Koog (models(), у Ollama — getModels()) против локального
 * JDK HttpServer, который отвечает как провайдер. Пути запросов задаёт Koog; тест лишь фиксирует, что ответы
 * сервера превращаются в правильные состояния.
 */
class KoogConnectionCheckerTest {
    private var server: HttpServer? = null
    private val seen = mutableListOf<String>()
    private val bodies = mutableListOf<String>()
    private val checker = KoogConnectionChecker()

    private class Reply(val status: Int, val body: String, val type: String = "application/json")

    @After fun tearDown() {
        server?.stop(0)
    }

    /** routes: путь запроса → ответ; всё остальное — 404. Возвращает корень сервера (http://127.0.0.1:port). */
    private fun serve(routes: Map<String, Reply>): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { ex ->
            val headers = listOf("Authorization", "x-api-key")
                .mapNotNull { h -> ex.requestHeaders.getFirst(h)?.let { "$h=$it" } }
            seen += "${ex.requestMethod} ${ex.requestURI} ${headers.joinToString(" ")}".trim()
            bodies += ex.requestBody.readBytes().decodeToString()
            val reply = routes[ex.requestURI.path] ?: Reply(404, """{"error":{"message":"not found"}}""")
            val bytes = reply.body.toByteArray()
            ex.responseHeaders.add("Content-Type", reply.type)
            ex.sendResponseHeaders(reply.status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    private fun openRouterModel(id: String, prompt: String = "0.0000004") = """
        {"id":"$id","canonical_slug":"$id","name":"$id","created":1,"description":"d",
         "pricing":{"prompt":"$prompt","completion":"$prompt"},"context_length":131072,
         "architecture":{"tokenizer":"Other","modality":"text->text"},
         "top_provider":{"context_length":131072,"is_moderated":false}}
    """.trimIndent()

    private val openRouterCatalog = """{"data":[
        ${openRouterModel("z-ai/glm-4.6")},
        ${openRouterModel("openai/gpt-4.1-mini")},
        ${openRouterModel("meta-llama/llama-3.3-70b-instruct:free", "0")}
    ]}"""

    private val completion = """{"id":"gen-1","object":"chat.completion","created":1,"model":"m",
        "choices":[{"index":0,"message":{"role":"assistant","content":"o"},"finish_reason":"length"}],
        "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""

    private fun openAiCatalog(vararg ids: String) =
        """{"object":"list","data":[${ids.joinToString(",") { """{"id":"$it","object":"model","created":1,"owned_by":"o"}""" }}]}"""

    @Test
    fun `openrouter lists the public catalog then proves the key with a one-token request to a free model`() = runBlocking {
        val root = serve(
            mapOf(
                "/api/v1/models" to Reply(200, openRouterCatalog),
                "/api/v1/chat/completions" to Reply(200, completion),
            ),
        )
        val result = checker.check(LlmProviderKind.OPENROUTER, root, "sk-or-test")

        assertTrue(result.toString(), result is ConnectionCheck.Ok)
        val models = (result as ConnectionCheck.Ok).models
        // Рекомендованная модель первой, остальные по алфавиту; «:free» помечается бесплатной.
        assertEquals(listOf("openai/gpt-4.1-mini", "meta-llama/llama-3.3-70b-instruct:free", "z-ai/glm-4.6"), models.map { it.id })
        assertTrue(models.first().recommended)
        assertEquals(true, models[1].isFree)
        assertEquals(2, seen.size)
        assertTrue(seen[0], seen[0].startsWith("GET /api/v1/models"))
        assertEquals("POST /api/v1/chat/completions Authorization=Bearer sk-or-test", seen[1])
        assertTrue(bodies[1], bodies[1].contains("meta-llama/llama-3.3-70b-instruct:free"))
    }

    @Test
    fun `openrouter bad key is caught by the probe even though the catalog is public`() = runBlocking {
        val root = serve(
            mapOf(
                "/api/v1/models" to Reply(200, openRouterCatalog),
                "/api/v1/chat/completions" to Reply(401, """{"error":{"message":"User not found.","code":401}}"""),
            ),
        )
        assertEquals(ConnectionCheck.Unauthorized("User not found."), checker.check(LlmProviderKind.OPENROUTER, root, "sk-or-bad"))
    }

    @Test
    fun `openrouter key without credits still counts as a valid key`() = runBlocking {
        val root = serve(
            mapOf(
                "/api/v1/models" to Reply(200, openRouterCatalog),
                "/api/v1/chat/completions" to Reply(402, """{"error":{"message":"Insufficient credits","code":402}}"""),
            ),
        )
        assertTrue(checker.check(LlmProviderKind.OPENROUTER, root, "sk-or-1") is ConnectionCheck.Ok)
    }

    @Test
    fun `403 is a block with the server reason, not a bad key`() = runBlocking {
        val root = serve(mapOf("/api/v1/models" to Reply(403, """{ "success": false, "error": "Access denied by security policy." }""")))
        assertEquals(ConnectionCheck.Blocked("Access denied by security policy."), checker.check(LlmProviderKind.OPENROUTER, root, "sk-or-1"))
    }

    @Test
    fun `openai lists models with bearer auth and 401 is a bad key without leaking it`() = runBlocking {
        val root = serve(mapOf("/v1/models" to Reply(200, openAiCatalog("gpt-4o-mini", "gpt-4.1-mini"))))
        val ok = checker.check(LlmProviderKind.OPENAI, root, "sk-1") as ConnectionCheck.Ok
        assertEquals(listOf("gpt-4.1-mini", "gpt-4o-mini"), ok.models.map { it.id })
        assertEquals(listOf("GET /v1/models Authorization=Bearer sk-1"), seen)

        server?.stop(0)
        val root2 = serve(mapOf("/v1/models" to Reply(401, """{"error":{"message":"Incorrect API key provided: sk-secret-123."}}""")))
        val bad = checker.check(LlmProviderKind.OPENAI, root2, "sk-secret-123")
        assertTrue(bad.toString(), bad is ConnectionCheck.Unauthorized)
        assertFalse(bad.toString(), bad.toString().contains("sk-secret-123"))
    }

    @Test
    fun `deepseek proves the key via its models list`() = runBlocking {
        val root = serve(mapOf("/models" to Reply(200, """{"object":"list","data":[{"id":"deepseek-chat","object":"model","owned_by":"deepseek"},{"id":"deepseek-reasoner","object":"model","owned_by":"deepseek"}]}""")))
        val ok = checker.check(LlmProviderKind.DEEPSEEK, root, "sk-ds") as ConnectionCheck.Ok
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), ok.models.map { it.id })
        assertTrue(ok.models.all { it.recommended })
        assertEquals(listOf("GET /models Authorization=Bearer sk-ds"), seen)

        server?.stop(0)
        val root2 = serve(mapOf("/models" to Reply(401, """{"error":{"message":"Authentication Fails, Your api key is invalid","type":"authentication_error"}}""")))
        assertEquals(
            ConnectionCheck.Unauthorized("Authentication Fails, Your api key is invalid"),
            checker.check(LlmProviderKind.DEEPSEEK, root2, "sk-bad"),
        )
    }

    @Test
    fun `mistral proves the key via its models list`() = runBlocking {
        val root = serve(
            mapOf(
                "/v1/models" to Reply(
                    200,
                    """{"object":"list","data":[{"id":"mistral-small-latest","object":"model","capabilities":{"completion_chat":true}},{"id":"codestral-latest","object":"model","capabilities":{"completion_chat":true}}]}""",
                ),
            ),
        )
        val ok = checker.check(LlmProviderKind.MISTRAL, root, "ms-1") as ConnectionCheck.Ok
        assertEquals(listOf("mistral-small-latest", "codestral-latest"), ok.models.map { it.id })
        assertEquals(listOf("GET /v1/models Authorization=Bearer ms-1"), seen)

        server?.stop(0)
        val root2 = serve(mapOf("/v1/models" to Reply(401, """{"message":"Unauthorized","request_id":"r"}""")))
        assertEquals(ConnectionCheck.Unauthorized("Unauthorized"), checker.check(LlmProviderKind.MISTRAL, root2, "bad"))
    }

    @Test
    fun `anthropic uses x-api-key`() = runBlocking {
        val root = serve(
            mapOf(
                "/v1/models" to Reply(
                    200,
                    """{"data":[{"id":"claude-sonnet-4-5-20250929","created_at":"2025-09-29T00:00:00Z","display_name":"Claude Sonnet 4.5","type":"model"}],"first_id":"a","has_more":false,"last_id":"a"}""",
                ),
            ),
        )
        val ok = checker.check(LlmProviderKind.ANTHROPIC, root, "sk-ant-1") as ConnectionCheck.Ok
        assertEquals("claude-sonnet-4-5-20250929", ok.models.single().id)
        // Датированный id тоже считается рекомендованным (курируемый список хранит алиас).
        assertTrue(ok.models.single().recommended)
        assertTrue(seen.single(), seen.single().startsWith("GET /v1/models") && seen.single().endsWith("x-api-key=sk-ant-1"))
    }

    @Test
    fun `google ids lose the models prefix and an invalid key 400 is a bad key`() = runBlocking {
        val root = serve(
            mapOf(
                "/v1beta/models" to Reply(
                    200,
                    """{"models":[{"name":"models/gemini-2.5-flash","version":"001","displayName":"Gemini 2.5 Flash","description":"d","inputTokenLimit":1048576,"outputTokenLimit":65536,"supportedGenerationMethods":["generateContent"],"thinking":true,"temperature":1.0,"maxTemperature":2.0,"topP":0.95,"topK":64}]}""",
                ),
            ),
        )
        val ok = checker.check(LlmProviderKind.GOOGLE, root, "AIza-1") as ConnectionCheck.Ok
        assertEquals(listOf("gemini-2.5-flash"), ok.models.map { it.id })
        assertTrue(seen.single(), seen.single().contains("key=AIza-1"))

        server?.stop(0)
        val root2 = serve(
            mapOf("/v1beta/models" to Reply(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""")),
        )
        assertEquals(
            ConnectionCheck.Unauthorized("API key not valid. Please pass a valid API key."),
            checker.check(LlmProviderKind.GOOGLE, root2, "bad"),
        )
    }

    private val ollamaShow = """{"details":{"format":"gguf","family":"llama","parameter_size":"3.2B","quantization_level":"Q4_K_M"},
        "model_info":{"general.architecture":"llama","general.parameter_count":3212749888,"llama.context_length":131072,"llama.embedding_length":3072},
        "capabilities":["completion","tools"]}"""

    @Test
    fun `ollama needs no key and lists local models with their context length`() = runBlocking {
        val root = serve(
            mapOf(
                "/api/tags" to Reply(200, """{"models":[{"name":"llama3.2:3b","model":"llama3.2:3b","size":2019393189,"modified_at":"2026-01-01T00:00:00Z","digest":"abc","details":{"format":"gguf","family":"llama","parameter_size":"3.2B"}}]}"""),
                "/api/show" to Reply(200, ollamaShow),
            ),
        )
        val ok = checker.check(LlmProviderKind.OLLAMA, root, null) as ConnectionCheck.Ok
        assertEquals(listOf(ModelInfo("llama3.2:3b", contextLength = 131_072)), ok.models)
        assertEquals(listOf("GET /api/tags", "POST /api/show"), seen)
    }

    @Test
    fun `ollama without pulled models reports an empty list, nothing listening is unreachable`() = runBlocking {
        val root = serve(mapOf("/api/tags" to Reply(200, """{"models":[]}""")))
        assertEquals(ConnectionCheck.NoModels, checker.check(LlmProviderKind.OLLAMA, root, null))
        assertTrue(checker.check(LlmProviderKind.OLLAMA, "http://127.0.0.1:1", null) is ConnectionCheck.Unreachable)
    }

    @Test
    fun `other server gets the full base url, a placeholder bearer when no key`() = runBlocking {
        val root = serve(mapOf("/v1/models" to Reply(200, openAiCatalog("qwen2.5-7b-instruct"))))
        val ok = checker.check(LlmProviderKind.OTHER, "$root/v1", "") as ConnectionCheck.Ok
        assertEquals(listOf("qwen2.5-7b-instruct"), ok.models.map { it.id })
        assertTrue(ok.listed)
        // Koog требует непустой ключ; локальные серверы заголовок игнорируют.
        assertEquals(listOf("GET /v1/models Authorization=Bearer not-needed"), seen)
    }

    @Test
    fun `other server with a bearer key checks it`() = runBlocking {
        val root = serve(mapOf("/v1/models" to Reply(401, """{"error":{"message":"Invalid API key (mock)"}}""")))
        assertEquals(ConnectionCheck.Unauthorized("Invalid API key (mock)"), checker.check(LlmProviderKind.OTHER, "$root/v1", "k"))
        assertEquals(listOf("GET /v1/models Authorization=Bearer k"), seen)
    }

    @Test
    fun `other server that cannot list models allows a manual model id`() = runBlocking {
        // 404 (нет /models) и нестандартный список (LM Studio без поля created) — модель вводится вручную.
        val root = serve(mapOf("/lms/v1/models" to Reply(200, """{"object":"list","data":[{"id":"x","object":"model"}]}""")))
        assertEquals(ConnectionCheck.Ok(emptyList(), listed = false), checker.check(LlmProviderKind.OTHER, "$root/v1", null))
        assertEquals(ConnectionCheck.Ok(emptyList(), listed = false), checker.check(LlmProviderKind.OTHER, "$root/lms/v1", null))
        server?.stop(0)
        val root2 = serve(mapOf("/v1/models" to Reply(200, """{"object":"list","data":[]}""")))
        assertEquals(ConnectionCheck.Ok(emptyList(), listed = false), checker.check(LlmProviderKind.OTHER, "$root2/v1", null))
    }

    @Test
    fun `cloud provider with an empty catalog is reported, other errors keep the status`() = runBlocking {
        val root = serve(
            mapOf(
                "/v1/models" to Reply(200, """{"object":"list","data":[]}"""),
                "/models" to Reply(500, """{"error":{"message":"boom"}}"""),
            ),
        )
        assertEquals(ConnectionCheck.NoModels, checker.check(LlmProviderKind.OPENAI, root, "sk-1"))
        assertEquals(ConnectionCheck.Failed(500, "boom"), checker.check(LlmProviderKind.DEEPSEEK, root, "sk-1"))
    }

    @Test
    fun `wrong address answering html is unreachable, 404 on a preset means a wrong address`() = runBlocking {
        val root = serve(mapOf("/v1/models" to Reply(200, "<html>hello</html>", "text/html")))
        assertTrue(checker.check(LlmProviderKind.OPENAI, root, "sk-1") is ConnectionCheck.Unreachable)
        assertTrue(checker.check(LlmProviderKind.MISTRAL, "$root/proxy", "sk-1") is ConnectionCheck.Unreachable)
    }

    @Test
    fun `missing key, bad url and public http are caught locally`() = runBlocking {
        assertEquals(ConnectionCheck.KeyMissing, checker.check(LlmProviderKind.OPENROUTER, LlmProviderKind.OPENROUTER.defaultBaseUrl, " "))
        assertTrue(checker.check(LlmProviderKind.OTHER, "garbage", null) is ConnectionCheck.InvalidUrl)
        val publicHttp = checker.check(LlmProviderKind.OTHER, "http://example.com/v1", null)
        assertTrue(publicHttp.toString(), publicHttp is ConnectionCheck.InvalidUrl && publicHttp.reason.contains("https://"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `nothing listening is unreachable`() = runBlocking {
        val result = checker.check(LlmProviderKind.OTHER, "http://127.0.0.1:1/v1", null)
        assertTrue(result.toString(), result is ConnectionCheck.Unreachable)
    }
}

package io.github.iampopovich.eculense.core.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/** Настоящий Ktor/OkHttp-клиент против локального JDK HttpServer: коды и тела как у провайдеров, без сети. */
class KtorConnectionCheckerTest {
    private var server: HttpServer? = null
    private val seen = mutableListOf<String>()
    private val checker = KtorConnectionChecker()

    private class Reply(val status: Int, val body: String, val type: String = "application/json")

    @After fun tearDown() {
        server?.stop(0)
    }

    /** routes: путь (без базы) → ответ; всё остальное — 404. Возвращает корень сервера. */
    private fun serve(routes: Map<String, Reply>): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { ex ->
            val headers = listOf("Authorization", "x-api-key", "anthropic-version")
                .mapNotNull { h -> ex.requestHeaders.getFirst(h)?.let { "$h=$it" } }
            seen += "${ex.requestMethod} ${ex.requestURI} ${headers.joinToString(" ")}".trim()
            val reply = routes[ex.requestURI.path] ?: Reply(404, """{"error":"not found"}""")
            val bytes = reply.body.toByteArray()
            ex.responseHeaders.add("Content-Type", reply.type)
            ex.sendResponseHeaders(reply.status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    private val openRouterModels = """
        {"data":[
          {"id":"z-ai/glm-4.6","name":"Z.AI: GLM 4.6","context_length":200000,"pricing":{"prompt":"0.0000004","completion":"0.0000017"}},
          {"id":"openai/gpt-4.1-mini","name":"OpenAI: GPT-4.1 Mini","context_length":1047576,"pricing":{"prompt":"0.0000004","completion":"0.0000016"}},
          {"id":"meta-llama/llama-3.3-70b-instruct:free","name":"Meta: Llama 3.3 70B (free)","context_length":131072,"pricing":{"prompt":"0","completion":"0"}}
        ]}
    """.trimIndent()

    @Test
    fun `openrouter proves the key via key endpoint then lists models curated first`() = runBlocking {
        val root = serve(
            mapOf(
                "/api/v1/key" to Reply(200, """{"data":{"label":"sk-or-v1-abc...","limit":null,"usage":0.1}}"""),
                "/api/v1/models" to Reply(200, openRouterModels),
            ),
        )
        val result = checker.check(LlmProviderKind.OPENROUTER, "$root/api/v1", "sk-or-test")

        assertTrue(result.toString(), result is ConnectionCheck.Ok)
        val models = (result as ConnectionCheck.Ok).models
        // openai/gpt-4.1-mini есть в рекомендованных → первым.
        assertEquals(listOf("openai/gpt-4.1-mini", "meta-llama/llama-3.3-70b-instruct:free", "z-ai/glm-4.6"), models.map { it.id })
        assertTrue(models.first().recommended)
        assertEquals(1_047_576L, models.first().contextLength)
        assertEquals(false, models.first().isFree)
        assertEquals(true, models[1].isFree)
        assertEquals("Meta: Llama 3.3 70B (free)", models[1].name)
        assertEquals(
            listOf("GET /api/v1/key Authorization=Bearer sk-or-test", "GET /api/v1/models Authorization=Bearer sk-or-test"),
            seen,
        )
    }

    @Test
    fun `openrouter 401 on key endpoint is a bad key and the public catalog is not even fetched`() = runBlocking {
        val root = serve(
            mapOf(
                "/api/v1/key" to Reply(401, """{"error":{"message":"User not found.","code":401}}"""),
                "/api/v1/models" to Reply(200, openRouterModels),
            ),
        )
        val result = checker.check(LlmProviderKind.OPENROUTER, "$root/api/v1", "sk-or-invalid")
        assertEquals(ConnectionCheck.Unauthorized("User not found."), result)
        assertEquals(1, seen.size)
    }

    @Test
    fun `403 is a block with the server reason, not a bad key`() = runBlocking {
        val root = serve(mapOf("/api/v1/key" to Reply(403, """{ "success": false, "error": "Access denied by security policy." }""")))
        val result = checker.check(LlmProviderKind.OPENROUTER, "$root/api/v1", "sk-or-invalid")
        assertEquals(ConnectionCheck.Blocked("Access denied by security policy."), result)
    }

    @Test
    fun `key never leaks into the reported detail`() = runBlocking {
        val root = serve(mapOf("/v1/models" to Reply(401, """{"error":{"message":"Incorrect API key provided: sk-secret-123."}}""")))
        val result = checker.check(LlmProviderKind.OPENAI, "$root/v1", "sk-secret-123")
        assertTrue(result is ConnectionCheck.Unauthorized)
        assertFalse(result.toString(), result.toString().contains("sk-secret-123"))
    }

    @Test
    fun `openai lists models with bearer auth`() = runBlocking {
        val root = serve(mapOf("/v1/models" to Reply(200, """{"object":"list","data":[{"id":"gpt-4o-mini","object":"model"},{"id":"gpt-4.1-mini","object":"model"}]}""")))
        val result = checker.check(LlmProviderKind.OPENAI, "$root/v1", "sk-1") as ConnectionCheck.Ok
        assertEquals(listOf("gpt-4.1-mini", "gpt-4o-mini"), result.models.map { it.id })
        assertEquals(listOf("GET /v1/models Authorization=Bearer sk-1"), seen)
    }

    @Test
    fun `custom server without key sends no auth and reads context length when present`() = runBlocking {
        val root = serve(mapOf("/v1/models" to Reply(200, """{"data":[{"id":"qwen2.5-7b-instruct","max_model_len":32768}]}""")))
        val result = checker.check(LlmProviderKind.OPENAI_COMPATIBLE, "$root/v1", "") as ConnectionCheck.Ok
        assertEquals(listOf(ModelInfo("qwen2.5-7b-instruct", contextLength = 32_768)), result.models)
        assertTrue(result.listed)
        assertEquals(listOf("GET /v1/models"), seen)
    }

    @Test
    fun `custom server that does not list models allows manual model id`() = runBlocking {
        val root = serve(emptyMap())
        val result = checker.check(LlmProviderKind.OPENAI_COMPATIBLE, "$root/v1", null)
        assertEquals(ConnectionCheck.Ok(emptyList(), listed = false), result)
    }

    @Test
    fun `cloud provider with an empty catalog is reported`() = runBlocking {
        val root = serve(mapOf("/v1/models" to Reply(200, """{"data":[]}""")))
        assertEquals(ConnectionCheck.NoModels, checker.check(LlmProviderKind.OPENAI, "$root/v1", "sk-1"))
    }

    @Test
    fun `wrong address answering html is unreachable, not success`() = runBlocking {
        val root = serve(mapOf("/v1/models" to Reply(200, "<html>hello</html>", "text/html")))
        val result = checker.check(LlmProviderKind.OPENAI, "$root/v1", "sk-1")
        assertTrue(result.toString(), result is ConnectionCheck.Unreachable)
    }

    @Test
    fun `404 on a cloud provider means a wrong address`() = runBlocking {
        val root = serve(emptyMap())
        assertTrue(checker.check(LlmProviderKind.OPENAI, "$root/v2", "sk-1") is ConnectionCheck.Unreachable)
    }

    @Test
    fun `anthropic uses x-api-key and version header`() = runBlocking {
        val root = serve(mapOf("/v1/models" to Reply(200, """{"data":[{"id":"claude-sonnet-4-5-20250929","display_name":"Claude Sonnet 4.5","type":"model"}],"has_more":false}""")))
        val result = checker.check(LlmProviderKind.ANTHROPIC, root, "sk-ant-1") as ConnectionCheck.Ok
        // Датированный id тоже считается рекомендованным (курируемый список хранит алиас).
        assertEquals(ModelInfo("claude-sonnet-4-5-20250929", "Claude Sonnet 4.5", recommended = true), result.models.single())
        assertEquals(listOf("GET /v1/models?limit=1000 x-api-key=sk-ant-1 anthropic-version=2023-06-01"), seen)
    }

    @Test
    fun `google key goes in the query, only chat models are kept and prefix is stripped`() = runBlocking {
        val root = serve(
            mapOf(
                "/v1beta/models" to Reply(
                    200,
                    """{"models":[
                      {"name":"models/gemini-2.5-flash","displayName":"Gemini 2.5 Flash","inputTokenLimit":1048576,"supportedGenerationMethods":["generateContent","countTokens"]},
                      {"name":"models/text-embedding-004","displayName":"Embedding","supportedGenerationMethods":["embedContent"]}
                    ]}""",
                ),
            ),
        )
        val result = checker.check(LlmProviderKind.GOOGLE, "$root/v1beta", "AIza-1") as ConnectionCheck.Ok
        assertEquals(listOf(ModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash", 1_048_576, recommended = true)), result.models)
        assertEquals(listOf("GET /v1beta/models?key=AIza-1&pageSize=1000"), seen)
    }

    @Test
    fun `google invalid key answers 400 and is mapped to a bad key`() = runBlocking {
        val root = serve(
            mapOf("/v1beta/models" to Reply(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""")),
        )
        assertEquals(
            ConnectionCheck.Unauthorized("API key not valid. Please pass a valid API key."),
            checker.check(LlmProviderKind.GOOGLE, "$root/v1beta", "bad"),
        )
    }

    @Test
    fun `other http errors keep the status`() = runBlocking {
        val root = serve(mapOf("/v1/models" to Reply(500, """{"error":{"message":"boom"}}""")))
        assertEquals(ConnectionCheck.Failed(500, "boom"), checker.check(LlmProviderKind.OPENAI, "$root/v1", "sk-1"))
    }

    @Test
    fun `missing key and bad url are caught locally`() = runBlocking {
        assertEquals(ConnectionCheck.KeyMissing, checker.check(LlmProviderKind.OPENROUTER, "https://openrouter.ai/api/v1", " "))
        assertTrue(checker.check(LlmProviderKind.OPENAI_COMPATIBLE, "garbage", null) is ConnectionCheck.InvalidUrl)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `nothing listening is unreachable`() = runBlocking {
        val result = checker.check(LlmProviderKind.OPENAI_COMPATIBLE, "http://127.0.0.1:1/v1", null)
        assertTrue(result.toString(), result is ConnectionCheck.Unreachable)
    }
}

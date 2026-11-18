package io.github.iampopovich.eculense.core.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException

/** Модель из каталога провайдера. Типы Koog наружу из :core:ai не выходят. */
data class ModelInfo(
    val id: String,
    val name: String = id,
    val contextLength: Long? = null,
    val isFree: Boolean? = null,
    val recommended: Boolean = false,
)

/** Итог «Проверить подключение»: доказан ли ключ и что сервер отдал. */
sealed interface ConnectionCheck {
    /** Ключ и адрес подтверждены. [listed] = false — свой сервер не отдаёт список моделей: id вводится вручную. */
    data class Ok(val models: List<ModelInfo>, val listed: Boolean = true) : ConnectionCheck
    data object NoModels : ConnectionCheck
    data object KeyMissing : ConnectionCheck
    data class InvalidUrl(val reason: String) : ConnectionCheck
    data class Unauthorized(val detail: String?) : ConnectionCheck
    data class Blocked(val detail: String?) : ConnectionCheck
    data class Unreachable(val detail: String?) : ConnectionCheck
    data class Failed(val status: Int, val detail: String?) : ConnectionCheck
}

interface ConnectionChecker {
    suspend fun check(provider: LlmProviderKind, baseUrl: String, apiKey: String?): ConnectionCheck
}

/** Локальная проверка адреса — до любого запроса в сеть. */
object EndpointUrl {
    sealed interface Result {
        data class Valid(val url: String) : Result
        data class Invalid(val reason: String) : Result
    }

    fun validate(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Invalid("Введите адрес сервера")
        if (text.any { it.isWhitespace() }) return Result.Invalid("В адресе не должно быть пробелов")
        val uri = try {
            URI(text)
        } catch (_: URISyntaxException) {
            return Result.Invalid("Некорректный адрес. Пример: https://openrouter.ai/api/v1")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result.Invalid("Адрес должен начинаться с http:// или https://")
        // При неверном порту URI молча переходит в «registry»-режим: host = null, authority = "host:99999".
        val authority = uri.rawAuthority.orEmpty()
        val portText = authority.substringAfterLast(':', "").takeIf { ':' in authority && !authority.endsWith("]") }
        if (portText != null && portText.isNotEmpty() && (portText.toIntOrNull() ?: 0) !in 1..65535) {
            return Result.Invalid("Неверный порт в адресе")
        }
        if (uri.host.isNullOrBlank()) return Result.Invalid("В адресе нет имени сервера")
        if (uri.rawQuery != null || uri.rawFragment != null) return Result.Invalid("Уберите из адреса параметры (?… и #…)")
        return Result.Valid(text.trimEnd('/'))
    }
}

/**
 * Проверка «ключ + адрес» и загрузка каталога, как `hermes model`: GET {base}/models с авторизацией провайдера.
 * Каталог OpenRouter публичный, поэтому ключ доказываем отдельно через GET {base}/key (401 на плохой ключ).
 * Свой Ktor-запрос, а не Koog `models()`: Koog отдаёт только id (без контекста/цены) и не знает /key.
 */
class KtorConnectionChecker(private val http: HttpClient = defaultClient()) : ConnectionChecker {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun check(provider: LlmProviderKind, baseUrl: String, apiKey: String?): ConnectionCheck {
        val base = when (val v = EndpointUrl.validate(baseUrl)) {
            is EndpointUrl.Result.Invalid -> return ConnectionCheck.InvalidUrl(v.reason)
            is EndpointUrl.Result.Valid -> v.url
        }
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        if (provider.needsKey && key == null) return ConnectionCheck.KeyMissing
        return try {
            when (provider) {
                LlmProviderKind.OPENROUTER -> {
                    get("$base/key", key, provider).failure(provider, key)?.let { return it }
                    catalog(get("$base/models", key, provider), provider, key, ::openAiModels)
                }
                LlmProviderKind.OPENAI, LlmProviderKind.OPENAI_COMPATIBLE ->
                    catalog(get("$base/models", key, provider), provider, key, ::openAiModels)
                LlmProviderKind.ANTHROPIC ->
                    catalog(get("$base/v1/models", key, provider, "limit" to "1000"), provider, key, ::anthropicModels)
                LlmProviderKind.GOOGLE ->
                    catalog(get("$base/models", key, provider, "key" to key.orEmpty(), "pageSize" to "1000"), provider, key, ::googleModels)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionCheck.Unreachable(redact(networkReason(e), key))
        }
    }

    private class Reply(val status: Int, val body: String)

    private suspend fun get(url: String, key: String?, provider: LlmProviderKind, vararg params: Pair<String, String>): Reply {
        val response = http.get(url) {
            params.forEach { (name, value) -> parameter(name, value) }
            when (provider) {
                LlmProviderKind.ANTHROPIC -> {
                    header("x-api-key", key)
                    header("anthropic-version", "2023-06-01")
                }
                LlmProviderKind.GOOGLE -> Unit
                else -> if (key != null) header("Authorization", "Bearer $key")
            }
        }
        return Reply(response.status.value, response.bodyAsText())
    }

    private fun Reply.failure(provider: LlmProviderKind, key: String?): ConnectionCheck? {
        val detail = redact(providerMessage.find(body)?.groupValues?.get(1), key)
        return when {
            status in 200..299 -> null
            status == 401 -> ConnectionCheck.Unauthorized(detail)
            // Google на плохой ключ отвечает 400 INVALID_ARGUMENT с причиной API_KEY_INVALID.
            status == 400 && provider == LlmProviderKind.GOOGLE && ("API_KEY_INVALID" in body || "API key not valid" in body) ->
                ConnectionCheck.Unauthorized(detail)
            status == 403 -> ConnectionCheck.Blocked(detail)
            status == 404 -> ConnectionCheck.Unreachable("по этому адресу нет API (404) — проверьте адрес")
            else -> ConnectionCheck.Failed(status, detail)
        }
    }

    private fun catalog(
        reply: Reply,
        provider: LlmProviderKind,
        key: String?,
        parse: (JsonElement) -> List<ModelInfo>?,
    ): ConnectionCheck {
        // Свой сервер может не уметь /models (llama.cpp старых версий и т.п.) — тогда модель вводится руками.
        if (reply.status == 404 && provider == LlmProviderKind.OPENAI_COMPATIBLE) return ConnectionCheck.Ok(emptyList(), listed = false)
        reply.failure(provider, key)?.let { return it }
        val models = try {
            parse(json.parseToJsonElement(reply.body))
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return ConnectionCheck.Unreachable("сервер ответил не в формате API (не JSON) — проверьте адрес")
        if (models.isEmpty()) {
            return if (provider == LlmProviderKind.OPENAI_COMPATIBLE) ConnectionCheck.Ok(emptyList(), listed = false) else ConnectionCheck.NoModels
        }
        return ConnectionCheck.Ok(order(models, provider.recommended))
    }

    private fun JsonElement.obj(name: String) = (this as? JsonObject)?.get(name)
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.long() = (this as? JsonPrimitive)?.longOrNull

    /** OpenAI/OpenRouter/LM Studio/vLLM: {"data":[{"id":…, "name"?, "context_length"|"max_model_len"?, "pricing"?}]} */
    private fun openAiModels(root: JsonElement): List<ModelInfo>? {
        val data = root.obj("data") as? JsonArray ?: return null
        return data.mapNotNull { item ->
            val id = item.obj("id").str() ?: return@mapNotNull null
            val pricing = item.obj("pricing")
            val free = pricing?.let { p -> listOf("prompt", "completion").all { p.obj(it).str()?.toDoubleOrNull() == 0.0 } }
            ModelInfo(
                id = id,
                name = item.obj("name").str() ?: id,
                contextLength = item.obj("context_length").long() ?: item.obj("max_model_len").long(),
                isFree = free,
            )
        }
    }

    private fun anthropicModels(root: JsonElement): List<ModelInfo>? {
        val data = root.obj("data") as? JsonArray ?: return null
        return data.mapNotNull { item ->
            val id = item.obj("id").str() ?: return@mapNotNull null
            ModelInfo(id, item.obj("display_name").str() ?: id)
        }
    }

    /** Только модели для чата (generateContent), id без префикса «models/». */
    private fun googleModels(root: JsonElement): List<ModelInfo>? {
        val list = root.obj("models") as? JsonArray ?: return null
        return list.mapNotNull { item ->
            val methods = (item.obj("supportedGenerationMethods") as? JsonArray)?.mapNotNull { it.str() }.orEmpty()
            if ("generateContent" !in methods) return@mapNotNull null
            val id = item.obj("name").str()?.removePrefix("models/") ?: return@mapNotNull null
            ModelInfo(id, item.obj("displayName").str() ?: id, item.obj("inputTokenLimit").long())
        }
    }

    companion object {
        private val providerMessage = Regex("\"(?:message|error)\"\\s*:\\s*\"([^\"]{1,200})\"")

        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 20_000
            }
        }

        /** Рекомендованные (в порядке курируемого списка) первыми, остальные по алфавиту. */
        fun order(models: List<ModelInfo>, recommended: List<String>): List<ModelInfo> {
            fun rank(id: String) = recommended.indexOfFirst { id == it || id.startsWith("$it-") }
            val (top, rest) = models.distinctBy { it.id }.partition { rank(it.id) >= 0 }
            return top.sortedWith(compareBy({ rank(it.id) }, { it.id })).map { it.copy(recommended = true) } + rest.sortedBy { it.id.lowercase() }
        }

        private fun redact(text: String?, key: String?): String? =
            if (text != null && key != null && key.length >= 4) text.replace(key, "***") else text

        private fun networkReason(e: Throwable): String {
            val chain = generateSequence(e) { it.cause }.take(8).toList()
            return when {
                chain.any { it is UnknownHostException } -> "сервер не найден (DNS) — проверьте адрес и интернет"
                chain.any { it is UnknownServiceException && it.message.orEmpty().contains("CLEARTEXT", true) } ->
                    "Android запрещает незашифрованный http к этому адресу — нужен https"
                chain.any { it is ConnectException } -> "не удалось подключиться — сервер не запущен или неверный адрес/порт"
                chain.any { it is HttpRequestTimeoutException || it is ConnectTimeoutException || it is SocketTimeoutException } ->
                    "сервер не ответил вовремя"
                chain.any { it is SSLException } -> "ошибка защищённого соединения (TLS)"
                else -> e.message ?: e::class.simpleName.orEmpty()
            }
        }
    }
}

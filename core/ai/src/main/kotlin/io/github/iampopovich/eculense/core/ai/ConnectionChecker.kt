package io.github.iampopovich.eculense.core.ai

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException

/** A model from the provider catalog. Koog types do not leak out of :core:ai. */
data class ModelInfo(
    val id: String,
    val name: String = id,
    val contextLength: Long? = null,
    val isFree: Boolean? = null,
    val recommended: Boolean = false,
)

/** Result of «Проверить подключение»: whether the key is proven and what the server returned. */
sealed interface ConnectionCheck {
    /** Key and URL confirmed. [listed] = false — a custom server does not return a model list: the id is entered manually. */
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
    /** [baseUrl] — the user's URL, only for [LlmProviderKind.OTHER]; for presets Koog sets the URL (pass null). */
    suspend fun check(provider: LlmProviderKind, baseUrl: String?, apiKey: String?): ConnectionCheck
}

/**
 * "Key + URL" check and model catalog — using Koog clients only, no custom paths or requests:
 * catalog = `LLMClient.models()` (for Ollama — `OllamaClient.getModels()`, models() is not implemented there).
 * For everyone except OpenRouter, the model list requires a key, so it also proves the key.
 * The OpenRouter catalog is public, and Koog 1.3.0 has no separate "check key" call, so we prove the key
 * with Koog's cheapest request — a 1-token `execute` (maxTokens = 1) against a free model from the catalog.
 * Koog errors (`KoogHttpClientException.statusCode/errorBody`) are mapped to [ConnectionCheck] states.
 */
class KoogConnectionChecker(
    private val clientFactory: (LlmProviderKind, String?, String?) -> LLMClient = { kind, otherUrl, key ->
        KoogClients.client(kind, key, otherBaseUrl = otherUrl)
    },
) : ConnectionChecker {

    override suspend fun check(provider: LlmProviderKind, baseUrl: String?, apiKey: String?): ConnectionCheck {
        // The URL is entered only for «Другого»; presets use the Koog client's default URL.
        val otherUrl = if (provider == LlmProviderKind.OTHER) {
            when (val v = EndpointUrl.validate(baseUrl.orEmpty())) {
                is EndpointUrl.Result.Invalid -> return ConnectionCheck.InvalidUrl(v.reason)
                is EndpointUrl.Result.Valid -> v.url
            }
        } else {
            null
        }
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() && provider.usesKey }
        if (provider.needsKey && key == null) return ConnectionCheck.KeyMissing
        val client = try {
            clientFactory(provider, otherUrl, key)
        } catch (e: IllegalStateException) {
            return ConnectionCheck.KeyMissing
        }
        return try {
            val models = catalog(client, provider)
            if (models.isEmpty()) {
                return if (provider == LlmProviderKind.OTHER) ConnectionCheck.Ok(emptyList(), listed = false) else ConnectionCheck.NoModels
            }
            if (provider == LlmProviderKind.OPENROUTER) proveKey(client, provider, models, key)?.let { return it }
            ConnectionCheck.Ok(order(models, provider.recommended))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(e, provider, key)
        } finally {
            runCatching { client.close() }
        }
    }

    private suspend fun catalog(client: LLMClient, provider: LlmProviderKind): List<ModelInfo> =
        if (client is OllamaClient) {
            client.getModels()
                .filter { it.capabilities.isEmpty() || LLMCapability.Completion in it.capabilities }
                .map { ModelInfo(it.name, contextLength = it.contextLength) }
        } else {
            client.models().map { model ->
                // Google returns resource names "models/…", but Koog expects a bare id in requests.
                val id = if (provider == LlmProviderKind.GOOGLE) model.id.removePrefix("models/") else model.id
                ModelInfo(
                    id = id,
                    contextLength = model.contextLength,
                    isFree = if (provider == LlmProviderKind.OPENROUTER) id.endsWith(":free") else null,
                )
            }
        }

    /** null — key accepted. Any response other than 401/403 means authorization passed (402 — key is valid, no funds). */
    private suspend fun proveKey(client: LLMClient, provider: LlmProviderKind, models: List<ModelInfo>, key: String?): ConnectionCheck? {
        val probe = models.firstOrNull { it.isFree == true }?.id
            ?: provider.recommended.firstOrNull { r -> models.any { it.id == r } }
            ?: models.first().id
        return try {
            client.execute(
                prompt("key-check", params = LLMParams(maxTokens = 1)) { user("ping") },
                LLModel(LLMProvider.OpenRouter, probe, listOf(LLMCapability.Completion), 8_192),
                emptyList(),
            )
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val http = httpError(e)
            when {
                http?.statusCode == 401 || http?.statusCode == 403 -> failure(e, provider, key)
                http == null && isNetwork(e) -> failure(e, provider, key)
                else -> null
            }
        }
    }

    private fun failure(e: Throwable, provider: LlmProviderKind, key: String?): ConnectionCheck {
        val http = httpError(e)
        val status = http?.statusCode
        if (status != null) {
            val body = http.errorBody.orEmpty()
            val detail = redact(providerMessage.find(body)?.groupValues?.get(1), key)
            return when {
                status == 401 -> ConnectionCheck.Unauthorized(detail)
                // For a bad key Google responds 400 INVALID_ARGUMENT with reason API_KEY_INVALID.
                status == 400 && provider == LlmProviderKind.GOOGLE && ("API_KEY_INVALID" in body || "API key not valid" in body) ->
                    ConnectionCheck.Unauthorized(detail)
                status == 403 -> ConnectionCheck.Blocked(detail)
                // A custom server may not support /models (old llama.cpp versions etc.) — the model is entered manually.
                status == 404 && provider == LlmProviderKind.OTHER -> ConnectionCheck.Ok(emptyList(), listed = false)
                status == 404 -> ConnectionCheck.Unreachable("по этому адресу нет API (404) — проверьте адрес")
                else -> ConnectionCheck.Failed(status, detail)
            }
        }
        if (!isNetwork(e) && chain(e).any { it is SerializationException || it is IllegalArgumentException }) {
            // The server responded, but not in the Koog client's API format (HTML, non-standard model list).
            return if (provider == LlmProviderKind.OTHER) {
                ConnectionCheck.Ok(emptyList(), listed = false)
            } else {
                ConnectionCheck.Unreachable("сервер ответил не в формате API — проверьте адрес")
            }
        }
        return ConnectionCheck.Unreachable(redact(networkReason(e), key))
    }

    companion object {
        private val providerMessage = Regex("\"(?:message|error)\"\\s*:\\s*\"([^\"]{1,200})\"")

        private fun chain(e: Throwable) = generateSequence(e) { it.cause }.take(10).toList()

        private fun httpError(e: Throwable): KoogHttpClientException? =
            chain(e).filterIsInstance<KoogHttpClientException>().firstOrNull { it.statusCode != null }

        private fun isNetwork(e: Throwable) = chain(e).any {
            it is UnknownHostException || it is ConnectException || it is SocketTimeoutException || it is SSLException ||
                it is HttpRequestTimeoutException || it is ConnectTimeoutException || it is UnknownServiceException
        }

        /** Recommended ones first (in curated-list order), the rest alphabetically. */
        fun order(models: List<ModelInfo>, recommended: List<String>): List<ModelInfo> {
            fun rank(id: String) = recommended.indexOfFirst { id == it || id.startsWith("$it-") }
            val (top, rest) = models.distinctBy { it.id }.partition { rank(it.id) >= 0 }
            return top.sortedWith(compareBy({ rank(it.id) }, { it.id })).map { it.copy(recommended = true) } + rest.sortedBy { it.id.lowercase() }
        }

        private fun redact(text: String?, key: String?): String? =
            if (text != null && key != null && key.length >= 4) text.replace(key, "***") else text

        private fun networkReason(e: Throwable): String {
            val chain = chain(e)
            return when {
                chain.any { it is UnknownHostException } -> "сервер не найден (DNS) — проверьте адрес и интернет"
                chain.any { it is UnknownServiceException && it.message.orEmpty().contains("CLEARTEXT", true) } ->
                    "Android запрещает незашифрованный http к этому адресу — нужен https"
                chain.any { it is ConnectException } -> "не удалось подключиться — сервер не запущен или неверный адрес/порт"
                chain.any { it is HttpRequestTimeoutException || it is ConnectTimeoutException || it is SocketTimeoutException } ->
                    "сервер не ответил вовремя"
                chain.any { it is SSLException } -> "ошибка защищённого соединения (TLS)"
                else -> chain.last().message ?: e.message ?: e::class.simpleName.orEmpty()
            }
        }
    }
}

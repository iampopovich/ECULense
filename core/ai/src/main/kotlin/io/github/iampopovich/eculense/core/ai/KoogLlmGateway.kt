package io.github.iampopovich.eculense.core.ai

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.IOException

internal object KoogClients {
    /**
     * Koog 1.3.0: удобные функции OpenAILLMClient(apiKey) и т.п. ищут фабрику HTTP через ServiceLoader,
     * а в Android-артефакте http-client-ktor нет META-INF/services → «No KoogHttpClient.Factory provider found».
     * Поэтому фабрику передаём явно, движок — OkHttp (его тянет koog-agents).
     */
    private val httpFactory: KoogHttpClient.Factory by lazy { KtorKoogHttpClient.Factory(HttpClient(OkHttp)) }

    private val timeouts = ConnectionTimeoutConfig(
        requestTimeoutMillis = 180_000,
        connectTimeoutMillis = 15_000,
        socketTimeoutMillis = 90_000,
    )

    fun provider(kind: LlmProviderKind): LLMProvider = when (kind) {
        LlmProviderKind.OPENAI, LlmProviderKind.OPENAI_COMPATIBLE -> LLMProvider.OpenAI
        LlmProviderKind.ANTHROPIC -> LLMProvider.Anthropic
        LlmProviderKind.GOOGLE -> LLMProvider.Google
        LlmProviderKind.OPENROUTER -> LLMProvider.OpenRouter
    }

    /** Модель задаёт пользователь строкой (id у провайдера), поэтому LLModel собираем сами. */
    fun model(settings: LlmSettings): LLModel {
        val capabilities = buildList {
            add(LLMCapability.Completion)
            add(LLMCapability.Temperature)
            add(LLMCapability.Tools)
            // OpenAILLMClient 1.3.0 выбирает эндпоинт по способности; без неё — «Cannot determine proper LLM params».
            if (provider(settings.provider) == LLMProvider.OpenAI) add(LLMCapability.OpenAIEndpoint.Completions)
        }
        return LLModel(
            provider = provider(settings.provider),
            id = settings.model,
            capabilities = capabilities,
            contextLength = 128_000,
        )
    }

    fun executor(settings: LlmSettings, apiKey: String?): PromptExecutor {
        fun key() = apiKey?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("Не задан API-ключ для ${settings.provider.title}")
        // Сохранённый адрес уже содержит версию API (…/api/v1, …/v1, …/v1beta), как его вводит пользователь;
        // Koog дописывает «/» к базе и относительные пути, поэтому пути задаём без префикса версии.
        val baseUrl = settings.baseUrl.trim().trimEnd('/').ifBlank { settings.provider.defaultBaseUrl }
        val client: LLMClient = when (settings.provider) {
            LlmProviderKind.OPENROUTER -> OpenRouterLLMClient(
                apiKey = key(),
                settings = OpenRouterClientSettings(
                    baseUrl = baseUrl,
                    chatCompletionsPath = "chat/completions",
                    modelsPath = "models",
                    embeddingsPath = "embeddings",
                    timeoutConfig = timeouts,
                ),
                httpClientFactory = httpFactory,
            )
            LlmProviderKind.OPENAI, LlmProviderKind.OPENAI_COMPATIBLE -> OpenAILLMClient(
                // Локальным серверам ключ обычно не нужен, но Koog требует непустую строку.
                apiKey = if (settings.provider.needsKey) key() else apiKey?.takeIf { it.isNotBlank() } ?: "not-needed",
                settings = OpenAIClientSettings(
                    baseUrl = baseUrl,
                    timeoutConfig = timeouts,
                    chatCompletionsPath = "chat/completions",
                    responsesAPIPath = "responses",
                    embeddingsPath = "embeddings",
                    moderationsPath = "moderations",
                    modelsPath = "models",
                ),
                httpClientFactory = httpFactory,
            )
            LlmProviderKind.ANTHROPIC -> AnthropicLLMClient(
                apiKey = key(),
                // 1.3.0 отправляет в API не model.id, а значение из modelVersionsMap — кладём туда модель пользователя.
                settings = AnthropicClientSettings(modelVersionsMap = mapOf(model(settings) to settings.model), baseUrl = baseUrl, timeoutConfig = timeouts),
                httpClientFactory = httpFactory,
            )
            LlmProviderKind.GOOGLE -> GoogleLLMClient(
                apiKey = key(),
                settings = GoogleClientSettings(baseUrl = baseUrl, timeoutConfig = timeouts, defaultPath = "models"),
                httpClientFactory = httpFactory,
            )
        }
        return MultiLLMPromptExecutor(client)
    }
}

/** Перевод исключений Koog/сети в понятный текст. Ключ вырезается, если вдруг попал в ответ сервера. */
internal object LlmErrors {
    private val providerMessage = Regex("\"(?:message|error)\"\\s*:\\s*\"([^\"]{1,200})\"")

    fun describe(error: Throwable, apiKey: String?): LlmException {
        if (error is LlmException) return error
        val chain = generateSequence(error) { it.cause }.take(10).toList()
        val http = chain.filterIsInstance<KoogHttpClientException>().firstOrNull { it.statusCode != null }
        val status = http?.statusCode
        val text = when {
            error is IllegalStateException && chain.none { it is KoogHttpClientException } -> error.message ?: "ИИ не настроен"
            status == 401 -> "Ошибка авторизации (401): неверный или отозванный API-ключ. Проверьте ключ в Настройки → ИИ-помощник."
            status == 403 -> "Доступ запрещён (403): у ключа нет прав или провайдер блокирует запросы из этой сети/региона (попробуйте VPN)."
            status == 402 -> "Недостаточно средств на счёте провайдера (402)."
            status == 404 -> "Модель или адрес не найдены (404): проверьте название модели."
            status == 429 -> "Слишком много запросов (429): подождите и повторите."
            status != null && status >= 500 -> "Сервер провайдера недоступен ($status). Попробуйте позже."
            status != null -> "Провайдер отклонил запрос ($status)."
            chain.any { it is IOException } -> "Нет связи с сервером ИИ: проверьте интернет и адрес сервера."
            else -> "Не удалось получить ответ ИИ (${error::class.simpleName})."
        }
        val detail = http?.errorBody?.let { providerMessage.find(it)?.groupValues?.get(1) }
        val full = if (detail != null) "$text Ответ сервера: «$detail»" else text
        val safe = apiKey?.takeIf { it.length >= 4 }?.let { full.replace(it, "***") } ?: full
        return LlmException(safe, status, error)
    }
}

internal class KoogLlmGateway(
    private val store: LlmSettingsStore,
    private val executorFactory: (LlmSettings, String?) -> PromptExecutor = KoogClients::executor,
) : LlmGateway {
    override fun stream(system: String, history: List<ChatMessage>): Flow<String> = flow {
        val settings = store.settings.first()?.takeIf { it.model.isNotBlank() } ?: throw LlmException(NOT_CONFIGURED)
        val apiKey = store.apiKey()
        val chat = prompt("ecu-chat") {
            system(system)
            history.forEach { message ->
                when (message.role) {
                    ChatMessage.Role.USER -> user(message.text)
                    ChatMessage.Role.ASSISTANT -> assistant(message.text)
                }
            }
        }
        val executor = try {
            executorFactory(settings, apiKey)
        } catch (e: Exception) {
            throw LlmErrors.describe(e, apiKey)
        }
        try {
            // catch{} ловит только ошибки апстрима (Koog/сеть), исключения коллектора не подменяются.
            emitAll(
                executor.executeStreaming(chat, KoogClients.model(settings))
                    .filterIsInstance<StreamFrame.TextDelta>()
                    .map { it.text }
                    .catch { e -> throw if (e is CancellationException) e else LlmErrors.describe(e, apiKey) },
            )
        } finally {
            executor.close()
        }
    }

    private companion object {
        const val NOT_CONFIGURED = "ИИ не настроен: откройте Настройки → ИИ-помощник"
    }
}

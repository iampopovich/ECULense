package io.github.iampopovich.eculense.core.ai

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.mistralai.MistralAIClientSettings
import ai.koog.prompt.executor.clients.mistralai.MistralAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Клиенты Koog по провайдеру. У пресетов адрес и пути — настройки клиентов Koog по умолчанию
 * (`OpenRouterClientSettings()`, `OpenAIClientSettings()`, `AnthropicClientSettings()`, `GoogleClientSettings()`,
 * `DeepSeekClientSettings()`, `MistralAIClientSettings()`, `OllamaClient` с `DEFAULT_BASE_URL`); от нас — только
 * таймауты. Адрес задаёт пользователь лишь у [LlmProviderKind.OTHER]: полный OpenAI-совместимый адрес (…/v1),
 * поэтому пути клиенту OpenAI там задаём относительно него.
 */
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

    private val defaults: Map<LlmProviderKind, String> by lazy {
        LlmProviderKind.entries.associateWith { kind ->
            when (kind) {
                LlmProviderKind.OPENROUTER -> OpenRouterClientSettings().baseUrl
                LlmProviderKind.OPENAI -> OpenAIClientSettings().baseUrl
                LlmProviderKind.ANTHROPIC -> AnthropicClientSettings().baseUrl
                LlmProviderKind.GOOGLE -> GoogleClientSettings().baseUrl
                LlmProviderKind.DEEPSEEK -> DeepSeekClientSettings().baseUrl
                LlmProviderKind.MISTRAL -> MistralAIClientSettings().baseUrl
                LlmProviderKind.OLLAMA -> OllamaClient.DEFAULT_BASE_URL
                LlmProviderKind.OTHER -> ""
            }
        }
    }

    /** Для показа в сводке; в клиенты пресетов адрес не передаётся. */
    fun defaultBaseUrl(kind: LlmProviderKind): String = defaults.getValue(kind)

    fun provider(kind: LlmProviderKind): LLMProvider = when (kind) {
        LlmProviderKind.OPENAI, LlmProviderKind.OTHER -> LLMProvider.OpenAI
        LlmProviderKind.ANTHROPIC -> LLMProvider.Anthropic
        LlmProviderKind.GOOGLE -> LLMProvider.Google
        LlmProviderKind.OPENROUTER -> LLMProvider.OpenRouter
        LlmProviderKind.DEEPSEEK -> LLMProvider.DeepSeek
        LlmProviderKind.MISTRAL -> LLMProvider.MistralAI
        LlmProviderKind.OLLAMA -> LLMProvider.Ollama
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

    /**
     * [otherBaseUrl] — адрес пользователя, используется только для [LlmProviderKind.OTHER].
     * [model] нужен только Anthropic: 1.3.0 отправляет в API не model.id, а значение из modelVersionsMap.
     * [presetBaseUrlForTests] — только для JVM-тестов (локальный HttpServer вместо провайдера); приложение его не задаёт,
     * и тогда настройки пресета — целиком Koog по умолчанию.
     */
    fun client(
        kind: LlmProviderKind,
        apiKey: String?,
        model: LLModel? = null,
        otherBaseUrl: String? = null,
        presetBaseUrlForTests: String? = null,
    ): LLMClient {
        fun key() = apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalStateException("Не задан API-ключ для ${kind.title}")
        val test = presetBaseUrlForTests
        return when (kind) {
            LlmProviderKind.OPENROUTER -> OpenRouterLLMClient(
                apiKey = key(),
                settings = if (test == null) OpenRouterClientSettings(timeoutConfig = timeouts) else OpenRouterClientSettings(baseUrl = test, timeoutConfig = timeouts),
                httpClientFactory = httpFactory,
            )
            LlmProviderKind.OPENAI -> OpenAILLMClient(
                apiKey = key(),
                settings = if (test == null) OpenAIClientSettings(timeoutConfig = timeouts) else OpenAIClientSettings(baseUrl = test, timeoutConfig = timeouts),
                httpClientFactory = httpFactory,
            )
            LlmProviderKind.ANTHROPIC -> {
                val versions = model?.let { mapOf(it to it.id) }
                AnthropicLLMClient(
                    apiKey = key(),
                    settings = when {
                        test != null && versions != null -> AnthropicClientSettings(modelVersionsMap = versions, baseUrl = test, timeoutConfig = timeouts)
                        test != null -> AnthropicClientSettings(baseUrl = test, timeoutConfig = timeouts)
                        versions != null -> AnthropicClientSettings(modelVersionsMap = versions, timeoutConfig = timeouts)
                        else -> AnthropicClientSettings(timeoutConfig = timeouts)
                    },
                    httpClientFactory = httpFactory,
                )
            }
            LlmProviderKind.GOOGLE -> GoogleLLMClient(
                apiKey = key(),
                settings = if (test == null) GoogleClientSettings(timeoutConfig = timeouts) else GoogleClientSettings(baseUrl = test, timeoutConfig = timeouts),
                httpClientFactory = httpFactory,
            )
            LlmProviderKind.DEEPSEEK -> DeepSeekLLMClient(
                apiKey = key(),
                settings = if (test == null) DeepSeekClientSettings(timeoutConfig = timeouts) else DeepSeekClientSettings(baseUrl = test, timeoutConfig = timeouts),
                httpClientFactory = httpFactory,
            )
            LlmProviderKind.MISTRAL -> MistralAILLMClient(
                apiKey = key(),
                settings = if (test == null) MistralAIClientSettings(timeoutConfig = timeouts) else MistralAIClientSettings(baseUrl = test, timeoutConfig = timeouts),
                httpClientFactory = httpFactory,
            )
            LlmProviderKind.OLLAMA ->
                if (test == null) OllamaClient(httpClientFactory = httpFactory, timeoutConfig = timeouts)
                else OllamaClient(httpClientFactory = httpFactory, baseUrl = test, timeoutConfig = timeouts)
            LlmProviderKind.OTHER -> OpenAILLMClient(
                // Локальным серверам ключ обычно не нужен, но Koog требует непустую строку.
                apiKey = apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: "not-needed",
                // Адрес пользователя уже содержит версию API (…/v1), поэтому пути — относительно него.
                settings = OpenAIClientSettings(
                    baseUrl = otherBaseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
                        ?: throw IllegalStateException("Не задан адрес сервера"),
                    timeoutConfig = timeouts,
                    chatCompletionsPath = "chat/completions",
                    responsesAPIPath = "responses",
                    embeddingsPath = "embeddings",
                    moderationsPath = "moderations",
                    modelsPath = "models",
                ),
                httpClientFactory = httpFactory,
            )
        }
    }

    fun executor(settings: LlmSettings, apiKey: String?, presetBaseUrlForTests: String? = null): PromptExecutor {
        val other = if (settings.provider == LlmProviderKind.OTHER) {
            // Адрес мог быть сохранён до появления проверки — http в интернет не пускаем и здесь.
            when (val v = EndpointUrl.validate(settings.baseUrl)) {
                is EndpointUrl.Result.Invalid -> throw IllegalStateException(v.reason)
                is EndpointUrl.Result.Valid -> v.url
            }
        } else {
            null
        }
        val key = apiKey.takeIf { settings.provider.usesKey }
        return MultiLLMPromptExecutor(client(settings.provider, key, model(settings), other, presetBaseUrlForTests))
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
    private val toolbox: VehicleToolbox? = null,
    private val historyProvider: ChatHistoryStore = InMemoryChatHistory(),
    /** Модель на телефоне (:core:ailocal); если выбрана — чат идёт через неё, облачные настройки не трогаются. */
    private val onDevice: OnDeviceLlm? = null,
    /** Диспетчер: вопрос → тип, эксперты, период (детерминированно, без модели). */
    private val router: (String) -> ChatRoute = { ChatRouter.route(it) },
    /** Сколько ждать одного эксперта в облаке; не успел — редактор получает «эксперт недоступен». */
    private val expertTimeoutMs: Long = 120_000,
    private val expertLog: (String) -> Unit = { msg -> runCatching { android.util.Log.w("EcuExperts", msg) } },
    private val executorFactory: (LlmSettings, String?) -> PromptExecutor = { s, key -> KoogClients.executor(s, key) },
) : LlmGateway {
    override val hasVehicleTools: Boolean get() = toolbox != null

    override suspend fun history(conversationId: String): List<ChatMessage> = historyProvider.transcript(conversationId)

    override suspend fun deleteConversation(conversationId: String) = historyProvider.delete(conversationId)

    override suspend fun provider(): ProviderInfo? =
        onDevice?.session()?.info
            ?: store.settings.first()?.takeIf { it.model.isNotBlank() }?.let { ProviderInfo(it.provider.name, it.provider.title, it.model) }

    override fun chat(conversationId: String, userText: String, useCarData: Boolean): Flow<ChatEvent> = channelFlow {
        val route = router(userText)
        val local = try {
            onDevice?.session()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("Модель на телефоне не загрузилась: ${e.message ?: e::class.simpleName}", cause = e)
        }
        if (local != null) {
            chatOnDevice(local, route, conversationId, userText, useCarData)
            // исполнитель принадлежит :core:ailocal (движок держится между вопросами) — не закрываем
            return@channelFlow
        }
        val settings = store.settings.first()?.takeIf { it.model.isNotBlank() } ?: throw LlmException(NOT_CONFIGURED)
        val apiKey = store.apiKey()
        val tools = toolbox?.takeIf { useCarData }
        val executor = try {
            executorFactory(settings, apiKey)
        } catch (e: Exception) {
            throw LlmErrors.describe(e, apiKey)
        }
        val model = KoogClients.model(settings)
        try {
            when {
                // без данных машины или вопрос не о машине («привет») — без экспертов, форма по вопросу
                tools == null || route.roles.isEmpty() ->
                    runAgent(executor, model, null, conversationId, userText, streaming = true, system = SystemPrompts.general(route.intent))
                // одна роль — отвечает сама, стримом, без редактора
                route.roles.size == 1 -> {
                    val role = route.roles.single()
                    send(ChatEvent.Status(role.statusText(route.periodDays)))
                    runAgent(
                        executor, model, tools, conversationId, userText, streaming = true,
                        system = SystemPrompts.direct(role, route), registry = tools.registry(role.tools), label = roleLabel(role, tools),
                    )
                    send(ChatEvent.Experts(listOf(role.title), emptyList()))
                }
                else -> {
                    val notes = experts(executor, model, tools, route, conversationId, userText)
                    send(ChatEvent.Experts(route.roles.map { it.title }, notes))
                    send(ChatEvent.Status(EDITOR_STATUS))
                    runAgent(executor, model, null, conversationId, userText, streaming = true, system = SystemPrompts.editor(route, notes))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LlmErrors.describe(e, apiKey)
        } finally {
            executor.close()
        }
    }

    /**
     * Модель на телефоне (≈ 90 с на вызов): один вызов «сразу в ролях» выбранных экспертов; данные — сводкой только из
     * нужных вопросу разделов (тренды за период для «как машина вела себя»), а не всё подряд.
     */
    private suspend fun kotlinx.coroutines.channels.ProducerScope<ChatEvent>.chatOnDevice(
        local: OnDeviceSession,
        route: ChatRoute,
        conversationId: String,
        userText: String,
        useCarData: Boolean,
    ) {
        val tools = toolbox?.takeIf { useCarData && local.supportsTools && route.roles.isNotEmpty() }
        // Модель без инструментов: данные машины — сводкой в системном промпте, числа считает приложение.
        val digest = if (useCarData && toolbox != null && !local.supportsTools && route.roles.isNotEmpty()) {
            send(ChatEvent.Status("Приложение собирает данные машины…"))
            toolbox.digest(route.digestSections, route.periodDays)
        } else {
            null
        }
        val system = when {
            !useCarData || toolbox == null || route.roles.isEmpty() -> SystemPrompts.general(route.intent)
            tools != null -> SystemPrompts.onDevice(route, null) + "\n" + SystemPrompts.VEHICLE_DATA
            else -> SystemPrompts.onDevice(route, digest)
        }
        val registry = tools?.registry(route.roles.flatMap { it.tools }.toSet())
        try {
            runAgent(local.executor, local.model, tools, conversationId, userText, streaming = local.streaming, system = system, registry = registry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("Модель на телефоне не ответила: ${e.message ?: e::class.simpleName}", cause = e)
        }
        if (useCarData && toolbox != null && route.roles.isNotEmpty()) send(ChatEvent.Experts(route.roles.map { it.title }, emptyList()))
    }

    /**
     * Эксперты параллельно: у каждого свой системный промпт роли и только свои инструменты, ≤ [EXPERT_TOOL_ROUNDS] кругов,
     * без ChatMemory (контекст — две последние реплики диалога). Текст экспертов пользователю не стримится — он уходит
     * редактору и в «Заметки экспертов». Ошибка или таймаут одного эксперта не валит ответ.
     */
    private suspend fun kotlinx.coroutines.channels.ProducerScope<ChatEvent>.experts(
        executor: PromptExecutor,
        model: LLModel,
        tools: VehicleToolbox,
        route: ChatRoute,
        conversationId: String,
        userText: String,
    ): List<ExpertNote> {
        val context = runCatching { historyProvider.transcript(conversationId).takeLast(2) }.getOrDefault(emptyList())
        val input = buildString {
            if (context.isNotEmpty()) {
                appendLine("Контекст диалога (последние реплики):")
                context.forEach { m ->
                    append(if (m.role == ChatMessage.Role.USER) "Пользователь: " else "Помощник: ").appendLine(m.text.take(CONTEXT_CHARS))
                }
                appendLine()
            }
            append("Вопрос пользователя: ").append(userText)
        }
        return coroutineScope {
            route.roles.map { role ->
                async {
                    send(ChatEvent.Status(role.statusText(route.periodDays)))
                    val text = try {
                        withTimeoutOrNull(expertTimeoutMs) {
                            AIAgent(
                                promptExecutor = executor,
                                llmModel = model,
                                strategy = VehicleChatStrategy.create(
                                    system = SystemPrompts.expert(role, route),
                                    // пользователю — только какие данные смотрит роль; текст заметки — редактору
                                    emit = { e -> if (e is ChatEvent.ToolCall) send(e) },
                                    label = roleLabel(role, tools),
                                    streaming = true,
                                    maxToolRounds = EXPERT_TOOL_ROUNDS,
                                ),
                                toolRegistry = tools.registry(role.tools),
                                systemPrompt = SystemPrompts.expert(role, route),
                            ).run(input, null)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null.also { expertLog("expert ${role.id} failed: ${e::class.simpleName}") }
                    }
                    if (text.isNullOrBlank()) ExpertNote(role.id, role.title, "Эксперт «${role.title}» недоступен — его данных в ответе нет.", failed = true)
                    else ExpertNote(role.id, role.title, text.trim())
                }
            }.awaitAll()
        }
    }

    /** «ИИ смотрит ошибки…» → «Диагност смотрит ошибки…». */
    private fun roleLabel(role: ExpertRole, tools: VehicleToolbox): (String) -> String = { name ->
        val label = tools.progressLabel(name)
        if (label.startsWith("ИИ ")) role.title + label.removePrefix("ИИ") else "${role.title}: $label"
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<ChatEvent>.runAgent(
        executor: PromptExecutor,
        model: LLModel,
        tools: VehicleToolbox?,
        conversationId: String,
        userText: String,
        streaming: Boolean,
        system: String = SystemPrompts.chat(withTools = tools != null),
        registry: ToolRegistry? = null,
        label: (String) -> String = { name -> tools?.progressLabel(name) ?: name },
    ) {
        val agent = AIAgent(
            promptExecutor = executor,
            llmModel = model,
            strategy = VehicleChatStrategy.create(
                system = system,
                emit = { send(it) },
                label = label,
                streaming = streaming,
            ),
            toolRegistry = registry ?: tools?.registry() ?: ToolRegistry.EMPTY,
            systemPrompt = system,
        ) {
            install(ChatMemory) {
                chatHistoryProvider = historyProvider
                addPreProcessor(TextOnlyHistory)
            }
        }
        agent.run(userText, conversationId)
    }

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
        const val EDITOR_STATUS = "Редактор собирает ответ из заметок экспертов…"
        const val EXPERT_TOOL_ROUNDS = 3
        const val CONTEXT_CHARS = 400
    }
}

package io.github.iampopovich.eculense.core.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Провайдеры = ровно пресеты koog-spring-boot-starter (META-INF/config/koog: anthropic, deepseek, google, mistral,
 * ollama, openai, openrouter) + [OTHER] для любого OpenAI-совместимого сервера. Порядок = порядок в мастере,
 * OpenRouter первым и по умолчанию (решение пользователя).
 *
 * Адреса и пути API не наши: у пресетов адрес и пути — только из настроек клиента Koog по умолчанию
 * (`OpenAIClientSettings()` и т.д.), в UI поля адреса нет и в настройках он не хранится. Адрес вводится и хранится
 * только у [OTHER]. [defaultBaseUrl] — для показа (сводка), из тех же настроек Koog.
 * [recommended] — курируемые модели, их показываем первыми в каталоге (если сервер их отдаёт).
 */
enum class LlmProviderKind(
    val title: String,
    private val key: Key,
    val recommended: List<String>,
) {
    OPENROUTER(
        "OpenRouter", Key.REQUIRED,
        listOf("openai/gpt-4.1-mini", "google/gemini-2.5-flash", "anthropic/claude-sonnet-4.5", "deepseek/deepseek-chat-v3.1", "openai/gpt-4.1"),
    ),
    OPENAI("OpenAI", Key.REQUIRED, listOf("gpt-4.1-mini", "gpt-4.1", "gpt-4o-mini")),
    ANTHROPIC("Anthropic", Key.REQUIRED, listOf("claude-sonnet-4-5", "claude-haiku-4-5")),
    GOOGLE("Google Gemini", Key.REQUIRED, listOf("gemini-2.5-flash", "gemini-2.5-pro")),
    DEEPSEEK("DeepSeek", Key.REQUIRED, listOf("deepseek-chat", "deepseek-reasoner")),
    MISTRAL("Mistral", Key.REQUIRED, listOf("mistral-small-latest", "mistral-medium-latest", "mistral-large-latest")),

    /** Локальный Ollama по адресу клиента Koog по умолчанию (localhost:11434). Ollama на другом ПК — через [OTHER] (…:11434/v1). */
    OLLAMA("Ollama", Key.NONE, emptyList()),

    /** LM Studio, vLLM, llama.cpp server, LocalAI, Ollama на ПК, прокси: полный OpenAI-совместимый адрес (…/v1). Ключ — если нужен. */
    OTHER("Другой (свой сервер)", Key.OPTIONAL, emptyList()),
    ;

    private enum class Key { REQUIRED, OPTIONAL, NONE }

    /** Без ключа провайдер не работает. */
    val needsKey: Boolean get() = key == Key.REQUIRED

    /** Показывать ли поле ключа вообще (у Ollama его нет). */
    val usesKey: Boolean get() = key != Key.NONE

    /** Адрес по умолчанию из настроек клиента Koog; у [OTHER] его нет — адрес вводит пользователь. */
    val defaultBaseUrl: String get() = KoogClients.defaultBaseUrl(this)

    companion object {
        val DEFAULT = OPENROUTER
    }
}

/** [baseUrl] — действующий адрес: сохранённый пользователем или адрес клиента Koog по умолчанию. */
data class LlmSettings(
    val provider: LlmProviderKind,
    val model: String,
    val baseUrl: String,
    val hasKey: Boolean,
)

class LlmSettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    /** null — ИИ не настроен (или сохранён провайдер, которого больше нет). */
    val settings: Flow<LlmSettings?> = dataStore.data.map { p ->
        val name = p[PROVIDER] ?: return@map null
        val legacy = name == LEGACY_CUSTOM
        val provider = if (legacy) LlmProviderKind.OTHER else LlmProviderKind.entries.firstOrNull { it.name == name } ?: return@map null
        val url = if (provider == LlmProviderKind.OTHER) {
            // У прежнего «Свой сервер» адрес по умолчанию в файл не писался — восстанавливаем его.
            p[BASE_URL] ?: if (legacy) LEGACY_CUSTOM_URL else ""
        } else {
            // У пресетов адрес всегда из Koog; сохранённый раньше (прокси) игнорируется и удаляется при следующем save.
            provider.defaultBaseUrl
        }
        LlmSettings(provider, p[MODEL].orEmpty(), url, hasKey = p[API_KEY] != null)
    }

    /**
     * Пустой apiKey = «не менять сохранённый». [baseUrl] хранится только у [LlmProviderKind.OTHER];
     * для пресетов адрес не храним вовсе — его задают настройки клиента Koog.
     */
    suspend fun save(provider: LlmProviderKind, model: String, baseUrl: String?, apiKey: String?) {
        dataStore.edit { p ->
            val previous = p[PROVIDER]
            p[PROVIDER] = provider.name
            p[MODEL] = model.trim()
            val url = baseUrl?.trim()?.trimEnd('/')
            if (provider != LlmProviderKind.OTHER || url.isNullOrBlank()) p.remove(BASE_URL) else p[BASE_URL] = url
            when {
                !provider.usesKey -> p.remove(API_KEY)
                !apiKey.isNullOrBlank() -> p[API_KEY] = cipher.encrypt(apiKey.trim())
                // Ключ одного провайдера не годится другому.
                previous != null && previous != provider.name -> p.remove(API_KEY)
            }
        }
    }

    /** Удалить сохранённый ключ (провайдер и модель остаются). */
    suspend fun clearKey() {
        dataStore.edit { it.remove(API_KEY) }
    }

    /** Забыть всё: провайдер, адрес, модель и ключ. */
    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    suspend fun apiKey(): String? = dataStore.data.first()[API_KEY]?.let { runCatching { cipher.decrypt(it) }.getOrNull() }

    private companion object {
        val PROVIDER = stringPreferencesKey("provider")
        val MODEL = stringPreferencesKey("model")
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")

        /** Имя и адрес по умолчанию прежнего пункта «Свой сервер (OpenAI-совместимый)» — читаются как [LlmProviderKind.OTHER]. */
        const val LEGACY_CUSTOM = "OPENAI_COMPATIBLE"
        const val LEGACY_CUSTOM_URL = "http://192.168.1.10:1234/v1"
    }
}

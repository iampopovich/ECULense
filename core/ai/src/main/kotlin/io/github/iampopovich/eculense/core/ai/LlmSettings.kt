package io.github.iampopovich.eculense.core.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Порядок = порядок в мастере подключения; OpenRouter — первым и по умолчанию (решение пользователя).
 * [defaultBaseUrl] — базовый адрес API в том виде, в каком его вводит пользователь (как в `hermes model`):
 * каталог моделей = GET {base}/models (у Anthropic — {base}/v1/models), чат — {base}/chat/completions.
 * [recommended] — курируемые модели, их показываем первыми в списке (если сервер их отдаёт).
 */
enum class LlmProviderKind(
    val title: String,
    val needsKey: Boolean,
    val defaultBaseUrl: String,
    val recommended: List<String>,
) {
    OPENROUTER(
        "OpenRouter", true, "https://openrouter.ai/api/v1",
        listOf("openai/gpt-4.1-mini", "google/gemini-2.5-flash", "anthropic/claude-sonnet-4.5", "deepseek/deepseek-chat-v3.1", "openai/gpt-4.1"),
    ),
    OPENAI("OpenAI", true, "https://api.openai.com/v1", listOf("gpt-4.1-mini", "gpt-4.1", "gpt-4o-mini")),
    ANTHROPIC("Anthropic", true, "https://api.anthropic.com", listOf("claude-sonnet-4-5", "claude-haiku-4-5")),
    GOOGLE("Google Gemini", true, "https://generativelanguage.googleapis.com/v1beta", listOf("gemini-2.5-flash", "gemini-2.5-pro")),

    /** LM Studio, Ollama (/v1), vLLM, llama.cpp server и любые OpenAI-совместимые API. Ключ необязателен. */
    OPENAI_COMPATIBLE("Свой сервер (OpenAI-совместимый)", false, "http://192.168.1.10:1234/v1", emptyList()),
    ;

    companion object {
        val DEFAULT = OPENROUTER
    }
}

/** [baseUrl] — действующий адрес: сохранённый или адрес провайдера по умолчанию. */
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
    /** null — ИИ не настроен (или сохранён провайдер, которого больше нет, например старый OLLAMA). */
    val settings: Flow<LlmSettings?> = dataStore.data.map { p ->
        val provider = p[PROVIDER]?.let { name -> LlmProviderKind.entries.firstOrNull { it.name == name } } ?: return@map null
        LlmSettings(provider, p[MODEL].orEmpty(), p[BASE_URL] ?: provider.defaultBaseUrl, hasKey = p[API_KEY] != null)
    }

    /** Пустой apiKey = «не менять сохранённый». Адрес по умолчанию не храним — обновится вместе с приложением. */
    suspend fun save(provider: LlmProviderKind, model: String, baseUrl: String?, apiKey: String?) {
        dataStore.edit { p ->
            val previous = p[PROVIDER]
            p[PROVIDER] = provider.name
            p[MODEL] = model.trim()
            val url = baseUrl?.trim()?.trimEnd('/')
            if (url.isNullOrBlank() || url == provider.defaultBaseUrl) p.remove(BASE_URL) else p[BASE_URL] = url
            when {
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
    }
}

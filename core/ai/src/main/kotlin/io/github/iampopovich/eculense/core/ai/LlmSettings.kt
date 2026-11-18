package io.github.iampopovich.eculense.core.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Порядок = порядок в выпадающем списке; OpenRouter — первым и по умолчанию (решение пользователя). */
enum class LlmProviderKind(
    val title: String,
    val needsKey: Boolean,
    val defaultBaseUrl: String?,
    val modelHint: String,
) {
    OPENROUTER("OpenRouter", true, null, "openai/gpt-4.1-mini"),
    OPENAI("OpenAI", true, null, "gpt-4.1-mini"),
    ANTHROPIC("Anthropic", true, null, "claude-sonnet-4-5"),
    GOOGLE("Google Gemini", true, null, "gemini-2.5-flash"),
    OPENAI_COMPATIBLE("OpenAI-совместимый сервер (LM Studio, vLLM, DeepSeek…)", false, "http://192.168.1.10:1234", "qwen2.5-7b-instruct"),
    OLLAMA("Ollama", false, "http://10.0.2.2:11434", "qwen2.5:3b"),
    ;

    companion object {
        val DEFAULT = OPENROUTER
    }
}

data class LlmSettings(
    val provider: LlmProviderKind,
    val model: String,
    val baseUrl: String?,
    val hasKey: Boolean,
)

class LlmSettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    val settings: Flow<LlmSettings?> = dataStore.data.map { p ->
        val provider = p[PROVIDER]?.let { name -> LlmProviderKind.entries.firstOrNull { it.name == name } } ?: return@map null
        LlmSettings(provider, p[MODEL].orEmpty(), p[BASE_URL], hasKey = p[API_KEY] != null)
    }

    /** Пустой apiKey = «не менять сохранённый». */
    suspend fun save(provider: LlmProviderKind, model: String, baseUrl: String?, apiKey: String?) {
        dataStore.edit { p ->
            p[PROVIDER] = provider.name
            p[MODEL] = model.trim()
            if (baseUrl.isNullOrBlank()) p.remove(BASE_URL) else p[BASE_URL] = baseUrl.trim()
            if (!apiKey.isNullOrBlank()) p[API_KEY] = cipher.encrypt(apiKey.trim())
        }
    }

    /** Удалить сохранённый ключ (провайдер и модель остаются). */
    suspend fun clearKey() {
        dataStore.edit { it.remove(API_KEY) }
    }

    suspend fun apiKey(): String? = dataStore.data.first()[API_KEY]?.let { runCatching { cipher.decrypt(it) }.getOrNull() }

    private companion object {
        val PROVIDER = stringPreferencesKey("provider")
        val MODEL = stringPreferencesKey("model")
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
    }
}

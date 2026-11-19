package io.github.iampopovich.eculense.core.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Providers = exactly the koog-spring-boot-starter presets (META-INF/config/koog: anthropic, deepseek, google, mistral,
 * ollama, openai, openrouter) + [OTHER] for any OpenAI-compatible server. Order = order in the wizard,
 * OpenRouter first and default (user's decision).
 *
 * API addresses and paths are not ours: for presets the address and paths come only from the Koog client's default
 * settings (`OpenAIClientSettings()` etc.); the UI has no address field and it is not stored in settings. The address is
 * entered and stored only for [OTHER]. [defaultBaseUrl] — for display (summary), from the same Koog settings.
 * [recommended] — curated models, shown first in the catalog (if the server returns them).
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

    /** Local Ollama at the Koog client's default address (localhost:11434). Ollama on another PC — via [OTHER] (…:11434/v1). */
    OLLAMA("Ollama", Key.NONE, emptyList()),

    /** LM Studio, vLLM, llama.cpp server, LocalAI, Ollama on a PC, proxies: full OpenAI-compatible address (…/v1). Key — if required. */
    OTHER("Other (custom server)", Key.OPTIONAL, emptyList()),
    ;

    private enum class Key { REQUIRED, OPTIONAL, NONE }

    /** The provider does not work without a key. */
    val needsKey: Boolean get() = key == Key.REQUIRED

    /** Whether to show the key field at all (Ollama has none). */
    val usesKey: Boolean get() = key != Key.NONE

    /** Default address from the Koog client settings; [OTHER] has none — the user enters the address. */
    val defaultBaseUrl: String get() = KoogClients.defaultBaseUrl(this)

    companion object {
        val DEFAULT = OPENROUTER
    }
}

/** [baseUrl] — effective address: saved by the user or the Koog client's default address. */
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
    /** null — AI is not configured (or a provider that no longer exists is saved). */
    val settings: Flow<LlmSettings?> = dataStore.data.map { p ->
        val name = p[PROVIDER] ?: return@map null
        val legacy = name == LEGACY_CUSTOM
        val provider = if (legacy) LlmProviderKind.OTHER else LlmProviderKind.entries.firstOrNull { it.name == name } ?: return@map null
        val url = if (provider == LlmProviderKind.OTHER) {
            // The former «custom server» item did not write its default address to the file — restore it.
            p[BASE_URL] ?: if (legacy) LEGACY_CUSTOM_URL else ""
        } else {
            // For presets the address always comes from Koog; a previously saved one (proxy) is ignored and removed on the next save.
            provider.defaultBaseUrl
        }
        LlmSettings(provider, p[MODEL].orEmpty(), url, hasKey = p[API_KEY] != null)
    }

    /**
     * Empty apiKey = "keep the saved one". [baseUrl] is stored only for [LlmProviderKind.OTHER];
     * for presets the address is not stored at all — the Koog client settings define it.
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
                // One provider's key is no good for another.
                previous != null && previous != provider.name -> p.remove(API_KEY)
            }
        }
    }

    /** Delete the saved key (provider and model remain). */
    suspend fun clearKey() {
        dataStore.edit { it.remove(API_KEY) }
    }

    /** Forget everything: provider, address, model and key. */
    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    suspend fun apiKey(): String? = dataStore.data.first()[API_KEY]?.let { runCatching { cipher.decrypt(it) }.getOrNull() }

    private companion object {
        val PROVIDER = stringPreferencesKey("provider")
        val MODEL = stringPreferencesKey("model")
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")

        /** Name and default address of the former «custom server (OpenAI-compatible)» item — read as [LlmProviderKind.OTHER]. */
        const val LEGACY_CUSTOM = "OPENAI_COMPATIBLE"
        const val LEGACY_CUSTOM_URL = "http://192.168.1.10:1234/v1"
    }
}

package io.github.iampopovich.eculense.core.ai

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LlmSettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val reversingCipher = object : SecretCipher {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(encoded: String) = encoded.reversed()
    }

    @Test
    fun `saves settings and stores key encrypted`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "llm.preferences_pb") }
        val store = LlmSettingsStore(dataStore, reversingCipher)

        store.save(LlmProviderKind.OPENAI, model = "gpt-4.1-mini", baseUrl = null, apiKey = "sk-123")

        // Адрес по умолчанию — из настроек клиента Koog, не наша строка.
        assertEquals(LlmSettings(LlmProviderKind.OPENAI, "gpt-4.1-mini", OpenAIClientSettings().baseUrl, hasKey = true), store.settings.first())
        assertEquals("sk-123", store.apiKey())
        assertEquals("321-ks", dataStore.data.first()[stringPreferencesKey("api_key")])
    }

    @Test
    fun `only a user override of the base url is stored`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "llm4.preferences_pb") }
        val store = LlmSettingsStore(dataStore, reversingCipher)
        store.save(LlmProviderKind.OTHER, "qwen", "http://127.0.0.1:8089/v1/", null)
        assertEquals("http://127.0.0.1:8089/v1", store.settings.first()?.baseUrl)
        store.save(LlmProviderKind.OPENROUTER, "m", OpenRouterClientSettings().baseUrl, "k")
        assertEquals(null, dataStore.data.first()[stringPreferencesKey("base_url")])
        assertEquals(OpenRouterClientSettings().baseUrl, store.settings.first()?.baseUrl)
        store.save(LlmProviderKind.OPENROUTER, "m", "https://proxy.example.com", "")
        assertEquals("https://proxy.example.com", store.settings.first()?.baseUrl)
    }

    @Test
    fun `old custom server config reads as Other with its address`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "legacy.preferences_pb") }
        dataStore.edit {
            it[stringPreferencesKey("provider")] = "OPENAI_COMPATIBLE"
            it[stringPreferencesKey("model")] = "qwen"
            it[stringPreferencesKey("base_url")] = "http://192.168.1.5:1234/v1"
        }
        val store = LlmSettingsStore(dataStore, reversingCipher)
        assertEquals(LlmSettings(LlmProviderKind.OTHER, "qwen", "http://192.168.1.5:1234/v1", hasKey = false), store.settings.first())
    }

    @Test
    fun `old custom server config saved with the former default address keeps that address`() = runTest {
        // Прежний адрес по умолчанию не сохранялся в файл — восстанавливаем его при чтении.
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "legacy2.preferences_pb") }
        dataStore.edit {
            it[stringPreferencesKey("provider")] = "OPENAI_COMPATIBLE"
            it[stringPreferencesKey("model")] = "qwen"
        }
        val store = LlmSettingsStore(dataStore, reversingCipher)
        assertEquals("http://192.168.1.10:1234/v1", store.settings.first()?.baseUrl)
        assertEquals(LlmProviderKind.OTHER, store.settings.first()?.provider)
    }

    @Test
    fun `switching provider without a new key drops the old key, clear forgets everything`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "llm5.preferences_pb") }
        val store = LlmSettingsStore(dataStore, reversingCipher)
        store.save(LlmProviderKind.OPENROUTER, "m", null, "sk-or-1")
        store.save(LlmProviderKind.OTHER, "qwen", "http://127.0.0.1:8089/v1", "")
        assertEquals(null, store.apiKey())
        store.clear()
        assertEquals(null, store.settings.first())
    }

    @Test
    fun `empty key keeps the previous one`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "llm2.preferences_pb") }
        val store = LlmSettingsStore(dataStore, reversingCipher)
        store.save(LlmProviderKind.ANTHROPIC, "claude-sonnet-4-5", null, "key-1")
        store.save(LlmProviderKind.ANTHROPIC, "claude-haiku-4-5", null, "")
        assertEquals("key-1", store.apiKey())
    }

    @Test
    fun `ollama never stores a key`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "llm6.preferences_pb") }
        val store = LlmSettingsStore(dataStore, reversingCipher)
        store.save(LlmProviderKind.OLLAMA, "llama3.2", null, "stray")
        assertEquals(null, store.apiKey())
        assertEquals(false, store.settings.first()?.hasKey)
    }

    @Test
    fun `presets are the Koog starter providers plus Other, OpenRouter first and default`() {
        assertEquals(LlmProviderKind.OPENROUTER, LlmProviderKind.DEFAULT)
        assertEquals(
            listOf("OpenRouter", "OpenAI", "Anthropic", "Google Gemini", "DeepSeek", "Mistral", "Ollama", "Другой (свой сервер)"),
            LlmProviderKind.entries.map { it.title },
        )
        assertEquals(listOf(LlmProviderKind.OLLAMA), LlmProviderKind.entries.filter { !it.usesKey })
        assertEquals(listOf(LlmProviderKind.OLLAMA, LlmProviderKind.OTHER), LlmProviderKind.entries.filter { !it.needsKey })
    }

    @Test
    fun `clearKey removes the stored key`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "llm3.preferences_pb") }
        val store = LlmSettingsStore(dataStore, reversingCipher)
        store.save(LlmProviderKind.OPENROUTER, "openai/gpt-4.1-mini", null, "sk-or-1")
        store.clearKey()
        assertEquals(null, store.apiKey())
        assertEquals(false, store.settings.first()?.hasKey)
    }
}

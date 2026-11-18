package io.github.iampopovich.eculense.core.ai

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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

        assertEquals(LlmSettings(LlmProviderKind.OPENAI, "gpt-4.1-mini", "https://api.openai.com/v1", hasKey = true), store.settings.first())
        assertEquals("sk-123", store.apiKey())
        assertEquals("321-ks", dataStore.data.first()[stringPreferencesKey("api_key")])
    }

    @Test
    fun `custom base url is kept, default one is not stored`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "llm4.preferences_pb") }
        val store = LlmSettingsStore(dataStore, reversingCipher)
        store.save(LlmProviderKind.OPENAI_COMPATIBLE, "qwen", "http://127.0.0.1:8089/v1/", null)
        assertEquals("http://127.0.0.1:8089/v1", store.settings.first()?.baseUrl)
        store.save(LlmProviderKind.OPENROUTER, "m", "https://openrouter.ai/api/v1", "k")
        assertEquals(null, dataStore.data.first()[stringPreferencesKey("base_url")])
        assertEquals("https://openrouter.ai/api/v1", store.settings.first()?.baseUrl)
    }

    @Test
    fun `switching provider without a new key drops the old key, clear forgets everything`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "llm5.preferences_pb") }
        val store = LlmSettingsStore(dataStore, reversingCipher)
        store.save(LlmProviderKind.OPENROUTER, "m", null, "sk-or-1")
        store.save(LlmProviderKind.OPENAI_COMPATIBLE, "qwen", "http://127.0.0.1:8089/v1", "")
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
    fun `openrouter is the default provider`() {
        assertEquals(LlmProviderKind.OPENROUTER, LlmProviderKind.DEFAULT)
        assertEquals(LlmProviderKind.OPENROUTER, LlmProviderKind.entries.first())
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

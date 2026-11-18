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

        assertEquals(LlmSettings(LlmProviderKind.OPENAI, "gpt-4.1-mini", null, hasKey = true), store.settings.first())
        assertEquals("sk-123", store.apiKey())
        assertEquals("321-ks", dataStore.data.first()[stringPreferencesKey("api_key")])
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

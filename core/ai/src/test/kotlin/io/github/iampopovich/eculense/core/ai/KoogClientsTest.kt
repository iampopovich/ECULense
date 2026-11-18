package io.github.iampopovich.eculense.core.ai

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KoogClientsTest {
    @Test
    fun `openai-compatible servers use the OpenAI provider`() {
        assertEquals(LLMProvider.OpenAI, KoogClients.provider(LlmProviderKind.OPENAI_COMPATIBLE))
        assertEquals(LLMProvider.Ollama, KoogClients.provider(LlmProviderKind.OLLAMA))
        assertEquals(LLMProvider.OpenRouter, KoogClients.provider(LlmProviderKind.OPENROUTER))
    }

    @Test
    fun `model id comes from settings`() {
        val model = KoogClients.model(LlmSettings(LlmProviderKind.ANTHROPIC, "claude-sonnet-4-5", null, true))
        assertEquals("claude-sonnet-4-5", model.id)
        assertEquals(LLMProvider.Anthropic, model.provider)
    }

    @Test
    fun `openai models declare the chat completions endpoint`() {
        // Без этой способности OpenAILLMClient 1.3.0 падает: «Cannot determine proper LLM params».
        val model = KoogClients.model(LlmSettings(LlmProviderKind.OPENAI, "gpt-4.1-mini", null, true))
        assertTrue(LLMCapability.OpenAIEndpoint.Completions in model.capabilities.orEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun `cloud provider without key fails fast`() {
        KoogClients.executor(LlmSettings(LlmProviderKind.OPENAI, "gpt-4.1-mini", null, false), apiKey = null)
    }

    @Test(expected = IllegalStateException::class)
    fun `openrouter without key fails fast`() {
        KoogClients.executor(LlmSettings(LlmProviderKind.OPENROUTER, "openai/gpt-4.1-mini", null, false), apiKey = " ")
    }

    @Test
    fun `every provider builds an executor`() {
        LlmProviderKind.entries.forEach { kind ->
            KoogClients.executor(LlmSettings(kind, kind.modelHint, kind.defaultBaseUrl, true), apiKey = "k").close()
        }
    }
}

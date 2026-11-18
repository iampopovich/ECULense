package io.github.iampopovich.eculense.core.ai

import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.mistralai.MistralAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KoogClientsTest {
    @Test
    fun `each preset maps to its own Koog provider, Other to OpenAI-compatible`() {
        assertEquals(
            mapOf(
                LlmProviderKind.OPENROUTER to LLMProvider.OpenRouter,
                LlmProviderKind.OPENAI to LLMProvider.OpenAI,
                LlmProviderKind.ANTHROPIC to LLMProvider.Anthropic,
                LlmProviderKind.GOOGLE to LLMProvider.Google,
                LlmProviderKind.DEEPSEEK to LLMProvider.DeepSeek,
                LlmProviderKind.MISTRAL to LLMProvider.MistralAI,
                LlmProviderKind.OLLAMA to LLMProvider.Ollama,
                LlmProviderKind.OTHER to LLMProvider.OpenAI,
            ),
            LlmProviderKind.entries.associateWith { KoogClients.provider(it) },
        )
    }

    @Test
    fun `default base urls are Koog client settings defaults, Other has none`() {
        assertEquals(
            mapOf(
                LlmProviderKind.OPENROUTER to OpenRouterClientSettings().baseUrl,
                LlmProviderKind.OPENAI to OpenAIClientSettings().baseUrl,
                LlmProviderKind.ANTHROPIC to AnthropicClientSettings().baseUrl,
                LlmProviderKind.GOOGLE to GoogleClientSettings().baseUrl,
                LlmProviderKind.DEEPSEEK to DeepSeekClientSettings().baseUrl,
                LlmProviderKind.MISTRAL to MistralAIClientSettings().baseUrl,
                LlmProviderKind.OLLAMA to OllamaClient.DEFAULT_BASE_URL,
                LlmProviderKind.OTHER to "",
            ),
            LlmProviderKind.entries.associateWith { it.defaultBaseUrl },
        )
    }

    @Test
    fun `model id comes from settings`() {
        val model = KoogClients.model(LlmSettings(LlmProviderKind.ANTHROPIC, "claude-sonnet-4-5", LlmProviderKind.ANTHROPIC.defaultBaseUrl, true))
        assertEquals("claude-sonnet-4-5", model.id)
        assertEquals(LLMProvider.Anthropic, model.provider)
    }

    @Test
    fun `openai models declare the chat completions endpoint`() {
        // Без этой способности OpenAILLMClient 1.3.0 падает: «Cannot determine proper LLM params».
        listOf(LlmProviderKind.OPENAI, LlmProviderKind.OTHER).forEach { kind ->
            val model = KoogClients.model(LlmSettings(kind, "gpt-4.1-mini", "https://api.openai.com", true))
            assertTrue(LLMCapability.OpenAIEndpoint.Completions in model.capabilities.orEmpty())
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `cloud provider without key fails fast`() {
        KoogClients.executor(LlmSettings(LlmProviderKind.OPENAI, "gpt-4.1-mini", LlmProviderKind.OPENAI.defaultBaseUrl, false), apiKey = null)
    }

    @Test(expected = IllegalStateException::class)
    fun `deepseek without key fails fast`() {
        KoogClients.executor(LlmSettings(LlmProviderKind.DEEPSEEK, "deepseek-chat", LlmProviderKind.DEEPSEEK.defaultBaseUrl, false), apiKey = " ")
    }

    @Test
    fun `ollama and other need no key`() {
        KoogClients.executor(LlmSettings(LlmProviderKind.OLLAMA, "llama3.2", LlmProviderKind.OLLAMA.defaultBaseUrl, false), apiKey = null).close()
        KoogClients.executor(LlmSettings(LlmProviderKind.OTHER, "m", "http://127.0.0.1:1234/v1", false), apiKey = null).close()
    }

    @Test
    fun `every provider builds an executor`() {
        LlmProviderKind.entries.forEach { kind ->
            val url = kind.defaultBaseUrl.ifBlank { "http://127.0.0.1:1234/v1" }
            KoogClients.executor(LlmSettings(kind, "m", url, true), apiKey = "k").close()
        }
    }
}

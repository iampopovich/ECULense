package io.github.iampopovich.eculense.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every prompt that produces user-visible text starts with the answer-language line of the app language. */
class SystemPromptsLanguageTest {
    private val trend = ChatRoute(ChatIntent.TREND, listOf(ExpertRoles.USAGE, ExpertRoles.DIAGNOSTICIAN), 180)
    private val note = ExpertNote("usage", ExpertRoles.USAGE.title, "- trips 12 → normal")

    private fun prompts(lang: String): Map<String, String> = buildMap {
        put("mechanic", SystemPrompts.mechanic(lang))
        put("chat", SystemPrompts.chat(withTools = false, lang = lang))
        put("chatWithTools", SystemPrompts.chat(withTools = true, lang = lang))
        put("chatWithDigest", SystemPrompts.chatWithDigest("Car: Kia", lang))
        ChatIntent.entries.forEach { i -> put("general.$i", SystemPrompts.general(i, lang)) }
        ExpertRoles.ALL.forEach { r ->
            put("expert.${r.id}", SystemPrompts.expert(r, trend, lang))
            put("direct.${r.id}", SystemPrompts.direct(r, trend, lang))
        }
        put("editor", SystemPrompts.editor(trend, listOf(note), lang))
        put("onDevice", SystemPrompts.onDevice(trend, "Trends: none", lang))
        put("onDeviceNoDigest", SystemPrompts.onDevice(trend, null, lang))
    }

    @Test
    fun `russian and english tags give the language line first`() {
        mapOf("ru" to "Russian", "en" to "English").forEach { (tag, name) ->
            val line = "Always answer in $name (language tag: $tag), regardless of the language of the question or the data."
            assertEquals(line, SystemPrompts.languageLine(tag))
            prompts(tag).forEach { (key, prompt) ->
                assertTrue("$tag/$key: $prompt", prompt.startsWith(line + "\n"))
                assertEquals("$tag/$key", 1, prompt.split("Always answer in").size - 1)
            }
        }
    }

    @Test
    fun `regional tags keep the full tag and the base language name`() {
        assertEquals(
            "Always answer in English (language tag: en-US), regardless of the language of the question or the data.",
            SystemPrompts.languageLine("en-US"),
        )
    }

    @Test
    fun `prompts mention number style of the answer language and keep the core rules`() {
        val p = SystemPrompts.general(ChatIntent.DIAGNOSE, "ru", withTools = true)
        listOf(
            "decimal separator",
            "Clearing a trouble code is not a repair",
            "no VIN and no coordinates",
            "only from the tool results",
            "at most one line",
        ).forEach { assertTrue(it, p.contains(it)) }
    }

    @Test
    fun `model-facing texts are English only`() {
        val cyrillic = Regex("[\\u0400-\\u04FF]")
        (prompts("ru").values + ExpertRoles.ALL.flatMap { listOf(it.title, it.focus, it.prompt) } + SystemPrompts.EXPERT_NOTE + SystemPrompts.VEHICLE_DATA)
            .forEach { assertFalse(it, cyrillic.containsMatchIn(it)) }
    }
}

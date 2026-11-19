package io.github.iampopovich.eculense.feature.aichat

import io.github.iampopovich.eculense.core.ai.ChatEvent
import io.github.iampopovich.eculense.core.ai.ChatStatus
import io.github.iampopovich.eculense.core.ai.ExpertNote
import io.github.iampopovich.eculense.core.ai.LlmException
import io.github.iampopovich.eculense.core.ai.LlmProblem
import io.github.iampopovich.eculense.core.ai.ExpertRoles
import io.github.iampopovich.eculense.core.ai.ProviderInfo
import io.github.iampopovich.eculense.core.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ChatTextsTest {
    @Test
    fun `every expert role has a display name`() {
        ExpertRoles.ALL.forEach { assertNotNull(it.id, ChatTexts.roleNameRes(it.id)) }
    }

    @Test
    fun `role status carries the period, an unknown role falls back to its id`() {
        assertEquals(
            UiText.Res(R.string.aichat_status_fuel, listOf(UiText.Plural(R.plurals.aichat_period_months, 6))),
            ChatTexts.status(ChatEvent.Status(ChatStatus.Role("fuel", 180))),
        )
        assertEquals(UiText.Res(R.string.aichat_status_editor), ChatTexts.status(ChatEvent.Status(ChatStatus.Editor)))
        assertEquals(UiText.Raw("newRole"), ChatTexts.status(ChatEvent.Status(ChatStatus.Role("newRole", 30))))
    }

    @Test
    fun `typed AI errors map to resources, the server reason is appended`() {
        assertEquals(UiText.Res(R.string.aichat_err_not_configured), ChatTexts.error(LlmException(LlmProblem.NotConfigured)))
        assertEquals(
            UiText.Res(R.string.aichat_err_with_server, listOf(UiText.Res(R.string.aichat_err_403), "Access denied")),
            ChatTexts.error(LlmException(LlmProblem.Forbidden, "Access denied")),
        )
        assertEquals(UiText.Res(R.string.aichat_err_server, listOf(503)), ChatTexts.error(LlmException(LlmProblem.ServerDown(503))))
        assertEquals(UiText.Raw("boom"), ChatTexts.error(IllegalStateException("boom")))
    }

    @Test
    fun `period buckets match the router`() {
        assertEquals(UiText.Res(R.string.aichat_period_week), ChatTexts.period(7))
        assertEquals(UiText.Res(R.string.aichat_period_month), ChatTexts.period(30))
        assertEquals(UiText.Res(R.string.aichat_period_year), ChatTexts.period(365))
        assertEquals(UiText.Plural(R.plurals.aichat_period_months, 3), ChatTexts.period(90))
        assertEquals(UiText.Plural(R.plurals.aichat_period_days, 45), ChatTexts.period(45))
    }

    @Test
    fun `tool label names the role that looks, or the AI`() {
        assertEquals(
            UiText.Res(R.string.aichat_tool_dtc_history, listOf(UiText.Res(R.string.aichat_role_diagnostician))),
            ChatTexts.toolCall(ChatEvent.ToolCall("dtcHistory", "diagnostician")),
        )
        assertEquals(
            UiText.Res(R.string.aichat_tool_default, listOf(UiText.Res(R.string.aichat_subject_ai))),
            ChatTexts.toolCall(ChatEvent.ToolCall("unknownTool")),
        )
    }

    @Test
    fun `experts and notes are named by role id`() {
        val failed = ExpertNote("engine", "legacy", "legacy text", failed = true)
        val info = ChatTexts.experts(ChatEvent.Experts(listOf("engine"), listOf(failed)))
        assertEquals(listOf(UiText.Res(R.string.aichat_role_engine)), info.roles)
        assertEquals(
            UiText.Res(R.string.aichat_expert_unavailable, listOf(UiText.Res(R.string.aichat_role_engine))),
            ChatTexts.noteText(failed),
        )
        assertEquals(UiText.Raw("note"), ChatTexts.noteText(ExpertNote("engine", "legacy", "note")))
        // a role without a resource shows its id
        assertEquals(listOf(UiText.Raw("newRole")), ChatTexts.experts(ChatEvent.Experts(listOf("newRole"), emptyList())).roles)
    }

    @Test
    fun `only the custom server provider name is localized`() {
        assertEquals(UiText.Raw("DeepSeek"), ChatTexts.providerTitle(ProviderInfo("DEEPSEEK", "DeepSeek", "m")))
        assertEquals(UiText.Res(R.string.aichat_provider_other), ChatTexts.providerTitle(ProviderInfo("OTHER", "legacy", "m")))
    }
}

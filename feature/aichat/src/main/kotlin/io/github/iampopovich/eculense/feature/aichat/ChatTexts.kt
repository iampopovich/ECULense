package io.github.iampopovich.eculense.feature.aichat

import androidx.annotation.StringRes
import io.github.iampopovich.eculense.core.ai.ChatEvent
import io.github.iampopovich.eculense.core.ai.ChatStatus
import io.github.iampopovich.eculense.core.ai.ExpertNote
import io.github.iampopovich.eculense.core.ai.ExpertRoles
import io.github.iampopovich.eculense.core.ai.LlmProviderKind
import io.github.iampopovich.eculense.core.ai.ProviderInfo
import io.github.iampopovich.eculense.core.ui.UiText

/**
 * Chat labels in the app language. The AI modules report ids (role id, tool name, [ChatStatus]); their own legacy
 * labels are used only for events without an id.
 */
internal object ChatTexts {
    private val roleNames: Map<String, Int> = mapOf(
        ExpertRoles.DIAGNOSTICIAN.id to R.string.aichat_role_diagnostician,
        ExpertRoles.ENGINE.id to R.string.aichat_role_engine,
        ExpertRoles.COOLING.id to R.string.aichat_role_cooling,
        ExpertRoles.ELECTRICAL.id to R.string.aichat_role_electrical,
        ExpertRoles.USAGE.id to R.string.aichat_role_usage,
        ExpertRoles.FUEL.id to R.string.aichat_role_fuel,
        ExpertRoles.SERVICE.id to R.string.aichat_role_service,
        ExpertRoles.MODEL_EXPERT.id to R.string.aichat_role_model_expert,
    )

    private val roleStatuses: Map<String, Int> = mapOf(
        ExpertRoles.DIAGNOSTICIAN.id to R.string.aichat_status_diagnostician,
        ExpertRoles.ENGINE.id to R.string.aichat_status_engine,
        ExpertRoles.COOLING.id to R.string.aichat_status_cooling,
        ExpertRoles.ELECTRICAL.id to R.string.aichat_status_electrical,
        ExpertRoles.USAGE.id to R.string.aichat_status_usage,
        ExpertRoles.FUEL.id to R.string.aichat_status_fuel,
        ExpertRoles.SERVICE.id to R.string.aichat_status_service,
        ExpertRoles.MODEL_EXPERT.id to R.string.aichat_status_model_expert,
    )

    /** Tool names of :core:aitools → what the AI is looking at. */
    private val toolLabels: Map<String, Int> = mapOf(
        "carProfile" to R.string.aichat_tool_car_profile,
        "tripStats" to R.string.aichat_tool_trip_stats,
        "listSessions" to R.string.aichat_tool_list_sessions,
        "sessionStats" to R.string.aichat_tool_session_stats,
        "sensorSummary" to R.string.aichat_tool_sensor_summary,
        "findings" to R.string.aichat_tool_findings,
        "dtcHistory" to R.string.aichat_tool_dtc_history,
        "maintenanceDue" to R.string.aichat_tool_maintenance_due,
        "trends" to R.string.aichat_tool_trends,
    )

    @StringRes
    fun roleNameRes(roleId: String): Int? = roleNames[roleId]

    fun roleName(roleId: String, fallback: String): UiText = roleNames[roleId]?.let { UiText.Res(it) } ?: UiText.Raw(fallback)

    fun toolCall(event: ChatEvent.ToolCall): UiText {
        val subject = event.roleId?.let { id -> roleNames[id]?.let { UiText.Res(it) } } ?: UiText.Res(R.string.aichat_subject_ai)
        return UiText.Res(toolLabels[event.toolName] ?: R.string.aichat_tool_default, listOf(subject))
    }

    fun status(event: ChatEvent.Status): UiText = when (val key = event.key) {
        null -> UiText.Raw(event.label)
        ChatStatus.PhoneThinking -> UiText.Res(R.string.aichat_status_phone_thinking)
        ChatStatus.CollectingData -> UiText.Res(R.string.aichat_status_collecting)
        ChatStatus.Editor -> UiText.Res(R.string.aichat_status_editor)
        is ChatStatus.Role -> roleStatuses[key.roleId]?.let { UiText.Res(it, listOf(period(key.periodDays))) } ?: UiText.Raw(event.label)
    }

    /** Same buckets as the router's period text: week, month, year, whole months, otherwise days. */
    fun period(days: Int): UiText = when {
        days == 7 -> UiText.Res(R.string.aichat_period_week)
        days == 30 -> UiText.Res(R.string.aichat_period_month)
        days == 365 -> UiText.Res(R.string.aichat_period_year)
        days % 30 == 0 && days in 60..330 -> UiText.Plural(R.plurals.aichat_period_months, days / 30)
        else -> UiText.Plural(R.plurals.aichat_period_days, days)
    }

    fun experts(event: ChatEvent.Experts): ExpertsInfo {
        val roles = if (event.roleIds.size == event.roles.size) {
            event.roleIds.zip(event.roles) { id, title -> roleName(id, title) }
        } else {
            event.roles.map { UiText.Raw(it) }
        }
        return ExpertsInfo(roles, event.notes)
    }

    fun noteTitle(note: ExpertNote): UiText = roleName(note.roleId, note.title)

    /** A failed expert's note is our own text, so it is localized; a real note is model output and shown as is. */
    fun noteText(note: ExpertNote): UiText =
        if (note.failed) UiText.Res(R.string.aichat_expert_unavailable, listOf(noteTitle(note))) else UiText.Raw(note.text)

    /** Brand names stay as they are; only «Other» is localized. */
    fun providerTitle(provider: ProviderInfo): UiText =
        if (provider.key == LlmProviderKind.OTHER.name) UiText.Res(R.string.aichat_provider_other) else UiText.Raw(provider.title)
}

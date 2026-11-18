package io.github.iampopovich.eculense.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object ConnectDestination : NavKey
@Serializable data object DashboardDestination : NavKey
@Serializable data object PitstopDestination : NavKey
@Serializable data object SettingsDestination : NavKey
@Serializable data object DtcDestination : NavKey
@Serializable data object SessionsDestination : NavKey
@Serializable data class SessionDetailDestination(val sessionId: Long) : NavKey
@Serializable data object AiSettingsDestination : NavKey
@Serializable data class AiChatDestination(val prefill: String? = null) : NavKey

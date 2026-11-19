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
@Serializable data object CarDestination : NavKey
@Serializable data object StatsDestination : NavKey
/** carId = 0 — new car. */
@Serializable data class CarEditDestination(val carId: Long = 0) : NavKey
@Serializable data object AnalysisDestination : NavKey
@Serializable data class FindingDestination(val findingId: Long) : NavKey
@Serializable data object DataPacksDestination : NavKey
@Serializable data object AboutDestination : NavKey
@Serializable data object SignalsetDestination : NavKey
@Serializable data object MaintenanceDestination : NavKey
/** 3.10: on-device model (module :core:ailocal; the AI wizard shows the item only if there is a RouteHandler). */
@Serializable data object LocalModelDestination : NavKey
/** 1.27: «Импорт и резервные копии» (:feature:backup); «Записи»/«Статистика» link here if there is a RouteHandler. */
@Serializable data object BackupDestination : NavKey

/**
 * Links from notifications: MainActivity receives the [EXTRA_SCREEN] extra and opens the screen on top of «Пит-стоп»
 * (if the feature with that screen is included). Strings, so the notification module doesn't need feature keys.
 */
object DeepLinks {
    const val EXTRA_SCREEN = "io.github.iampopovich.eculense.SCREEN"
    const val ANALYSIS = "analysis"
    const val MAINTENANCE = "maintenance"

    fun destination(screen: String?): NavKey? = when (screen) {
        ANALYSIS -> AnalysisDestination
        MAINTENANCE -> MaintenanceDestination
        else -> null
    }
}

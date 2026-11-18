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
/** carId = 0 — новая машина. */
@Serializable data class CarEditDestination(val carId: Long = 0) : NavKey
@Serializable data object AnalysisDestination : NavKey
@Serializable data class FindingDestination(val findingId: Long) : NavKey
@Serializable data object DataPacksDestination : NavKey
@Serializable data object AboutDestination : NavKey
@Serializable data object SignalsetDestination : NavKey
@Serializable data object MaintenanceDestination : NavKey
/** 3.10: модель на телефоне (модуль :core:ailocal; мастер ИИ показывает пункт, только если есть RouteHandler). */
@Serializable data object LocalModelDestination : NavKey

/**
 * Ссылки из уведомлений: MainActivity получает экстру [EXTRA_SCREEN] и открывает экран поверх «Пит-стопа»
 * (если фича с этим экраном подключена). Строки — чтобы модулю уведомлений не нужны были ключи фич.
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

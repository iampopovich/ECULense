package io.github.iampopovich.eculense.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object ConnectDestination : NavKey
/** Adapter settings (A11): manual protocol, extra init commands, adaptive timing; opened from the connect screen. */
@Serializable data object AdapterSettingsDestination : NavKey
/** Adapter traffic log (:feature:obdlog), an item of the Settings hub. */
@Serializable data object ObdLogDestination : NavKey
@Serializable data object DashboardDestination : NavKey

/** Screens that hide the bottom navigation (driving mode). */
interface FullScreenDestination

/** C6: driving mode with a few big tiles (:feature:dashboard). */
@Serializable data object DrivingModeDestination : NavKey, FullScreenDestination

/** C1: everything the car reports, grouped by system, and the dashboard PID choice (:feature:cardata). */
@Serializable data object CarDataDestination : NavKey
@Serializable data object PitstopDestination : NavKey
@Serializable data object SettingsDestination : NavKey
@Serializable data object DtcDestination : NavKey

/** B8: on-board test results (mode 06), a Pit-stop item of the DTC feature. */
@Serializable data object Mode06Destination : NavKey

/** R-D: the vehicle's modules (engine, transmission, ABS...) and their codes, a Pit-stop item of the DTC feature. */
@Serializable data object VehicleModulesDestination : NavKey
@Serializable data object SessionsDestination : NavKey
@Serializable data class SessionDetailDestination(val sessionId: Long) : NavKey
@Serializable data object AiSettingsDestination : NavKey
@Serializable data class AiChatDestination(val prefill: String? = null) : NavKey
@Serializable data object CarDestination : NavKey
@Serializable data object StatsDestination : NavKey
/** carId = 0 — new car. */
@Serializable data class CarEditDestination(val carId: Long = 0) : NavKey
@Serializable data object AnalysisDestination : NavKey
/** R-H: engine norms of the active car. */
@Serializable data object CarNormsDestination : NavKey
@Serializable data class FindingDestination(val findingId: Long) : NavKey
@Serializable data object DataPacksDestination : NavKey
@Serializable data object AboutDestination : NavKey
@Serializable data object SignalsetDestination : NavKey
@Serializable data object MaintenanceDestination : NavKey
/** 3.10: on-device model (module :core:ailocal; the AI wizard shows the item only if there is a RouteHandler). */
@Serializable data object LocalModelDestination : NavKey
/** 1.27: «Импорт и резервные копии» (:feature:backup); «Записи»/«Статистика» link here if there is a RouteHandler. */
@Serializable data object BackupDestination : NavKey
/** App language picker (:feature:settings). */
@Serializable data object LanguageDestination : NavKey

/**
 * Links from notifications: MainActivity receives the [EXTRA_SCREEN] extra and opens the screen on top of «Пит-стоп»
 * (if the feature with that screen is included). Strings, so the notification module doesn't need feature keys.
 */
object DeepLinks {
    const val EXTRA_SCREEN = "io.github.iampopovich.eculense.SCREEN"
    /** C9: the widget's «Connect»: the activity connects to the last adapter once it is in front. */
    const val EXTRA_CONNECT_LAST = "io.github.iampopovich.eculense.CONNECT_LAST"
    const val ANALYSIS = "analysis"
    const val MAINTENANCE = "maintenance"
    /** C4: «new code during the trip» opens the DTC screen. */
    const val DTC = "dtc"
    /** R1.5: the widget's «Connect» without a remembered adapter opens the adapter screen (Settings hub). */
    const val CONNECT = "connect"

    fun destination(screen: String?): NavKey? = when (screen) {
        ANALYSIS -> AnalysisDestination
        MAINTENANCE -> MaintenanceDestination
        DTC -> DtcDestination
        CONNECT -> ConnectDestination
        else -> null
    }

    /**
     * Back stack for a deep link: the tab of the hub that lists [target], then [target]. null when no included
     * feature registers [target] in a hub or that hub's tab is not part of the app (then the app just opens).
     */
    fun backStack(target: NavKey, hubs: List<HubEntry>, tabs: List<NavKey>): List<NavKey>? {
        val tab = hubs.firstOrNull { it.destination == target }?.hub?.tab ?: return null
        return if (tab in tabs) listOf(tab, target) else null
    }
}

/** B6: quick health check on Pit-stop (:feature:health). */
@Serializable data object HealthCheckDestination : NavKey
@Serializable data class HealthReportDestination(val checkId: Long) : NavKey

/** C4: live alert thresholds (Settings hub of :feature:dashboard). */
@Serializable data object AlertSettingsDestination : NavKey

/** D1: fuel log (:feature:fuel); Stats shows a header action when a RouteHandler for it exists. */
@Serializable data object FuelLogDestination : NavKey

/** D2: expenses and cost of ownership (:feature:fuel). */
@Serializable data object ExpensesDestination : NavKey

/** D4: one trip from the trips table (:feature:stats). */
@Serializable data class TripDetailDestination(val tripId: Long) : NavKey

/** D5: fuel trims against rpm and load for a recording (:feature:sessions); [compareWith] = a second recording. */
@Serializable data class EngineMapDestination(val sessionId: Long, val compareWith: Long? = null) : NavKey

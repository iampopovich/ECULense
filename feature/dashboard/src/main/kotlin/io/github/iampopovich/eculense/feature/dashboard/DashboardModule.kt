package io.github.iampopovich.eculense.feature.dashboard

import io.github.iampopovich.eculense.core.data.AlertPrefs
import io.github.iampopovich.eculense.core.data.AutomationPrefs
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.EventMarker
import io.github.iampopovich.eculense.core.data.InMemoryAlertPrefs
import io.github.iampopovich.eculense.core.data.LiveAlerts
import io.github.iampopovich.eculense.core.navigation.AlertSettingsDestination
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import io.github.iampopovich.eculense.core.navigation.DashboardDestination
import io.github.iampopovich.eculense.core.navigation.DrivingModeDestination
import io.github.iampopovich.eculense.core.navigation.TopLevelEntry
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

@OptIn(KoinExperimentalAPI::class)
val dashboardModule = module {
    viewModel {
        DashboardViewModel(
            get(), get(), get(), get(),
            alerts = getOrNull<LiveAlerts>(), marker = getOrNull<EventMarker>(), cars = getOrNull<CarRepository>(), automation = getOrNull<AutomationPrefs>(),
            rangeBasis = rangeBasisOf(this),
            extended = getOrNull<io.github.iampopovich.eculense.core.data.ExtendedSensors>(),
        )
    }
    viewModel { AlertSettingsViewModel(getOrNull<AlertPrefs>() ?: InMemoryAlertPrefs(), saveScope = getOrNull<kotlinx.coroutines.CoroutineScope>(io.github.iampopovich.eculense.core.obd.AppScope)) }
    navigation<DashboardDestination> { DashboardRoute() }
    navigation<DrivingModeDestination> { DrivingModeRoute() }
    navigation<AlertSettingsDestination> { AlertSettingsRoute() }
    single(named("alert-settings-hub")) {
        HubEntry(Hub.SETTINGS, AlertSettingsDestination, R.string.alerts_hub_title, R.string.alerts_hub_subtitle, R.drawable.ic_warning, order = 13)
    }
    single(named("dashboard")) { TopLevelEntry(DashboardDestination, R.string.dash_tab, R.drawable.ic_dashboard, order = 0) }
}

/** D3 range inputs when the fuel log is in the build (it lives in :core:data, always present; nullables for tests). */
private fun rangeBasisOf(scope: org.koin.core.scope.Scope): kotlinx.coroutines.flow.Flow<io.github.iampopovich.eculense.core.data.RangeBasis?> {
    val cars = scope.getOrNull<CarRepository>()
    val fuel = scope.getOrNull<io.github.iampopovich.eculense.core.data.FuelRepository>()
    val trips = scope.getOrNull<io.github.iampopovich.eculense.core.data.TripRepository>()
    val prefs = scope.getOrNull<io.github.iampopovich.eculense.core.data.FuelPrefs>()
    return if (cars == null || fuel == null || trips == null || prefs == null) kotlinx.coroutines.flow.flowOf(null)
    else io.github.iampopovich.eculense.core.data.observeRangeBasis(cars, fuel, trips, prefs)
}

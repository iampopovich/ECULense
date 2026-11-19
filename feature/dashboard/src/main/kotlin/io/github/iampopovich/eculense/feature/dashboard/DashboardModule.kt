package io.github.iampopovich.eculense.feature.dashboard

import io.github.iampopovich.eculense.core.navigation.DashboardDestination
import io.github.iampopovich.eculense.core.navigation.TopLevelEntry
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

@OptIn(KoinExperimentalAPI::class)
val dashboardModule = module {
    viewModelOf(::DashboardViewModel)
    navigation<DashboardDestination> { DashboardRoute() }
    single(named("dashboard")) { TopLevelEntry(DashboardDestination, R.string.dash_tab, R.drawable.ic_dashboard, order = 0) }
}

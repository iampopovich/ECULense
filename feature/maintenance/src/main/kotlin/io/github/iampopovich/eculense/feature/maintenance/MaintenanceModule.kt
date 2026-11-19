package io.github.iampopovich.eculense.feature.maintenance

import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import io.github.iampopovich.eculense.core.navigation.MaintenanceDestination
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** «Maintenance» — a «Pit stop» item after «Analysis» (3.9 item 2). Works offline and without AI. */
@OptIn(KoinExperimentalAPI::class)
val maintenanceModule = module {
    viewModel { MaintenanceViewModel(get(), get()) }
    navigation<MaintenanceDestination> { MaintenanceRoute() }
    single(named("maintenance-hub")) {
        HubEntry(Hub.PITSTOP, MaintenanceDestination, R.string.maint_hub_title, R.string.maint_hub_subtitle, R.drawable.ic_maintenance, order = 17)
    }
}

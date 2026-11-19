package io.github.iampopovich.eculense.feature.maintenance

import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import io.github.iampopovich.eculense.core.navigation.MaintenanceDestination
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** «Обслуживание» — a «Пит-стопа» item after «Анализа» (3.9 item 2). Works offline and without AI. */
@OptIn(KoinExperimentalAPI::class)
val maintenanceModule = module {
    viewModel { MaintenanceViewModel(get(), get()) }
    navigation<MaintenanceDestination> { MaintenanceRoute() }
    single(named("maintenance-hub")) {
        HubEntry(Hub.PITSTOP, MaintenanceDestination, "Обслуживание", "ТО, пробег и напоминания", R.drawable.ic_maintenance, order = 17)
    }
}

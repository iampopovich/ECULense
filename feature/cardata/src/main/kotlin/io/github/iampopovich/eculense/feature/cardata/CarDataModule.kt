package io.github.iampopovich.eculense.feature.cardata

import io.github.iampopovich.eculense.core.navigation.CarDataDestination
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import io.github.iampopovich.eculense.core.navigation.RouteHandler
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/**
 * C1 «Данные машины»: a Pit-stop hub item (also opened from the dashboard by its key). Removing this module leaves
 * the dashboard with its default / last saved PID selection.
 */
@OptIn(KoinExperimentalAPI::class)
val carDataModule = module {
    viewModel { CarDataViewModel(get(), get(), get()) }
    navigation<CarDataDestination> { CarDataRoute() }
    single(named("cardata-route")) { RouteHandler(CarDataDestination::class) }
    single(named("cardata-hub")) {
        HubEntry(Hub.PITSTOP, CarDataDestination, R.string.cardata_hub_title, R.string.cardata_hub_subtitle, R.drawable.ic_gauge, order = 12)
    }
}

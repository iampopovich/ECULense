package io.github.iampopovich.eculense.feature.dtc

import io.github.iampopovich.eculense.core.data.AnalyticsRepository
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.dtcdb.DtcDescriptions
import io.github.iampopovich.eculense.core.navigation.DtcDestination
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import io.github.iampopovich.eculense.core.navigation.Mode06Destination
import io.github.iampopovich.eculense.core.navigation.VehicleModulesDestination
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** DTCs are an item in the «Пит-стоп» hub, not a tab. */
@OptIn(KoinExperimentalAPI::class)
val dtcModule = module {
    single<DtcDescriber> {
        val descriptions = get<DtcDescriptions>()
        val cars = get<CarRepository>()
        DtcDescriber { codes -> descriptions.describeAll(codes, cars.observeActiveCar().first()?.brand) }
    }
    single<DtcHistorySource> {
        val cars = get<CarRepository>()
        val analytics = get<AnalyticsRepository>()
        DtcHistorySource {
            val id = cars.activeCarId() ?: return@DtcHistorySource emptyList()
            analytics.dtcEvents(id).map { DtcHistoryEvent(it.code, it.kind, it.atMs) }
        }
    }
    viewModel { DtcViewModel(get(), get(), get(), routeHandlers = getAll(), historySource = get()) }
    viewModel { Mode06ViewModel(get()) }
    viewModel { VehicleModulesViewModel(get(), get(), get(), logger = get()) }
    navigation<DtcDestination> { DtcRoute() }
    navigation<Mode06Destination> { Mode06Route() }
    navigation<VehicleModulesDestination> { VehicleModulesRoute() }
    single(named("dtc-hub")) {
        HubEntry(Hub.PITSTOP, DtcDestination, R.string.dtc_hub_title, R.string.dtc_hub_subtitle, R.drawable.ic_dtc, order = 10)
    }
    single(named("mode06-hub")) {
        HubEntry(Hub.PITSTOP, Mode06Destination, R.string.mode06_hub_title, R.string.mode06_hub_subtitle, R.drawable.ic_check, order = 11)
    }
    // R-D: the car's other modules (read-only module scan).
    single(named("modules-hub")) {
        HubEntry(Hub.PITSTOP, VehicleModulesDestination, R.string.modules_hub_title, R.string.modules_hub_subtitle, R.drawable.ic_dtc, order = 12)
    }
}

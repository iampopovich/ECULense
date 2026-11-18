package io.github.iampopovich.eculense.feature.dtc

import io.github.iampopovich.eculense.core.data.AnalyticsRepository
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.dtcdb.DtcDescriptions
import io.github.iampopovich.eculense.core.navigation.DtcDestination
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** Ошибки — пункт хаба «Пит-стоп», а не вкладка. */
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
    navigation<DtcDestination> { DtcRoute() }
    single(named("dtc-hub")) {
        HubEntry(Hub.PITSTOP, DtcDestination, "Ошибки (DTC)", "Чтение и сброс кодов с расшифровкой", R.drawable.ic_dtc, order = 10)
    }
}

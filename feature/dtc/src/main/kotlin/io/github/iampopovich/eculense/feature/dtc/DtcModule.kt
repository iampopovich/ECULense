package io.github.iampopovich.eculense.feature.dtc

import io.github.iampopovich.eculense.core.navigation.DtcDestination
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** Ошибки — пункт хаба «Пит-стоп», а не вкладка. */
@OptIn(KoinExperimentalAPI::class)
val dtcModule = module {
    single<DtcDescriptions> { AssetDtcDescriptions(androidContext()) }
    viewModel { DtcViewModel(get(), get(), get(), routeHandlers = getAll()) }
    navigation<DtcDestination> { DtcRoute() }
    single(named("dtc-hub")) {
        HubEntry(Hub.PITSTOP, DtcDestination, "Ошибки (DTC)", "Чтение и сброс кодов с расшифровкой", R.drawable.ic_dtc, order = 10)
    }
}

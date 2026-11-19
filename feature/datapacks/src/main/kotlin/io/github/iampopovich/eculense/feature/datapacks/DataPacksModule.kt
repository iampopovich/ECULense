package io.github.iampopovich.eculense.feature.datapacks

import io.github.iampopovich.eculense.core.navigation.AboutDestination
import io.github.iampopovich.eculense.core.navigation.DataPacksDestination
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import io.github.iampopovich.eculense.core.navigation.SignalsetDestination
import io.github.iampopovich.eculense.core.obdb.obdbModule
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** «Данные и справочники» and «О приложении» — items of the «Настройки» hub. */
@OptIn(KoinExperimentalAPI::class)
val dataPacksFeatureModule = module {
    includes(obdbModule)
    viewModel { DataPacksViewModel(get()) }
    viewModel { SignalsetViewModel(get(), get()) }
    navigation<DataPacksDestination> { DataPacksRoute() }
    navigation<AboutDestination> { AboutRoute() }
    navigation<SignalsetDestination> { SignalsetRoute() }
    single(named("datapacks-hub")) {
        HubEntry(Hub.SETTINGS, DataPacksDestination, "Данные и справочники", "Справочник моделей, базы кодов ошибок", R.drawable.ic_datapacks, order = 30)
    }
    single(named("about-hub")) {
        HubEntry(Hub.SETTINGS, AboutDestination, "О приложении", "Версия, лицензии и источники данных", R.drawable.ic_about, order = 90)
    }
}

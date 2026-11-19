package io.github.iampopovich.eculense.feature.connect

import io.github.iampopovich.eculense.core.navigation.ConnectDestination
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** Connection screen: an item of the «Настройки» hub (and the target of the «Подключить» button on the dashboard), not a tab. */
@OptIn(KoinExperimentalAPI::class)
val connectModule = module {
    viewModelOf(::ConnectViewModel)
    navigation<ConnectDestination> { ConnectRoute() }
    single(named("connect-hub")) {
        HubEntry(Hub.SETTINGS, ConnectDestination, "Адаптер", "Выбор и подключение ELM327", R.drawable.ic_adapter, order = 10)
    }
}

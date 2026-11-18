package io.github.iampopovich.eculense.feature.connect

import io.github.iampopovich.eculense.core.navigation.ConnectDestination
import io.github.iampopovich.eculense.core.navigation.TopLevelEntry
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

@OptIn(KoinExperimentalAPI::class)
val connectModule = module {
    viewModelOf(::ConnectViewModel)
    navigation<ConnectDestination> { ConnectRoute() }
    single(named("connect")) { TopLevelEntry(ConnectDestination, "Адаптер", R.drawable.ic_adapter, order = 0) }
}

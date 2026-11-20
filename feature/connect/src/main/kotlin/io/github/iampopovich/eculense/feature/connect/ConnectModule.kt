package io.github.iampopovich.eculense.feature.connect

import io.github.iampopovich.eculense.core.data.AutomationPrefs
import io.github.iampopovich.eculense.core.navigation.AdapterSettingsDestination
import io.github.iampopovich.eculense.core.navigation.ConnectDestination
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import org.koin.core.annotation.KoinExperimentalAPI
import io.github.iampopovich.eculense.core.obd.AdapterPrefs
import io.github.iampopovich.eculense.core.obd.InMemoryAdapterPrefs
import io.github.iampopovich.eculense.core.obd.ReplayLogHolder
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** Connection screen: an item of the Settings hub (and the target of the Connect button on the dashboard), not a tab. */
@OptIn(KoinExperimentalAPI::class)
val connectModule = module {
    viewModel { ConnectViewModel(get(), getOrNull<ReplayLogHolder>()) }
    viewModel { AdapterSettingsViewModel(getOrNull<AdapterPrefs>() ?: InMemoryAdapterPrefs(), get(), getOrNull<AutomationPrefs>()) }
    navigation<ConnectDestination> { ConnectRoute() }
    navigation<AdapterSettingsDestination> { AdapterSettingsRoute() }
    single(named("connect-hub")) {
        HubEntry(Hub.SETTINGS, ConnectDestination, R.string.connect_hub_title, R.string.connect_hub_subtitle, R.drawable.ic_adapter, order = 10)
    }
}

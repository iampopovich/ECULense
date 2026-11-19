package io.github.iampopovich.eculense.feature.aisettings

import io.github.iampopovich.eculense.core.navigation.AiSettingsDestination
import io.github.iampopovich.eculense.core.navigation.BuildCapabilities
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import io.github.iampopovich.eculense.core.navigation.LocalModelDestination
import io.github.iampopovich.eculense.core.navigation.RouteHandler
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** AI settings — an item in the «Настройки» hub (and the target of the «Настроить ИИ» button in the chat). */
@OptIn(KoinExperimentalAPI::class)
val aiSettingsModule = module {
    // «На устройстве» — only if the :core:ailocal module is included (its screen and OnDeviceLlm); the cloud build
    // (BuildCapabilities.onDeviceModels = false) shows a hint about the «Локально» build instead of the item.
    viewModel {
        AiSettingsViewModel(
            get(), get(), getOrNull(),
            onDeviceRoute = getAll<RouteHandler>().any { it.routeClass == LocalModelDestination::class },
            onDeviceInOtherBuild = getOrNull<BuildCapabilities>()?.onDeviceModels == false,
        )
    }
    navigation<AiSettingsDestination> { AiSettingsRoute() }
    single(named("aisettings-hub")) {
        HubEntry(Hub.SETTINGS, AiSettingsDestination, "ИИ-помощник", "Провайдер, адрес, ключ, модель", R.drawable.ic_ai_settings, order = 20)
    }
}

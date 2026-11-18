package io.github.iampopovich.eculense.feature.aisettings

import io.github.iampopovich.eculense.core.navigation.AiSettingsDestination
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** Настройки ИИ — пункт хаба «Настройки» (и цель кнопки «Настроить ИИ» в чате). */
@OptIn(KoinExperimentalAPI::class)
val aiSettingsModule = module {
    viewModelOf(::AiSettingsViewModel)
    navigation<AiSettingsDestination> { AiSettingsRoute() }
    single(named("aisettings-hub")) {
        HubEntry(Hub.SETTINGS, AiSettingsDestination, "ИИ-помощник", "Провайдер, адрес, ключ, модель", R.drawable.ic_ai_settings, order = 20)
    }
}

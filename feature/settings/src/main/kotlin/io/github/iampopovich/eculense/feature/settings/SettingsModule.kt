package io.github.iampopovich.eculense.feature.settings

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.navigation.SettingsDestination
import io.github.iampopovich.eculense.core.navigation.TopLevelEntry
import io.github.iampopovich.eculense.core.navigation.forHub
import io.github.iampopovich.eculense.core.ui.HubCard
import io.github.iampopovich.eculense.core.ui.HubEmptyText
import io.github.iampopovich.eculense.core.ui.HubScaffold
import org.koin.compose.getKoin
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** Вкладка «Настройки»: хаб, в который фичи сами добавляют пункты (адаптер, ИИ-провайдеры, …). */
@OptIn(KoinExperimentalAPI::class)
val settingsModule = module {
    navigation<SettingsDestination> { SettingsRoute() }
    single(named("settings")) { TopLevelEntry(SettingsDestination, "Настройки", R.drawable.ic_settings, order = 20) }
}

@Composable
internal fun SettingsRoute() {
    val koin = getKoin()
    val entries = remember { koin.getAll<HubEntry>().forHub(Hub.SETTINGS) }
    val navigator = LocalNavigator.current
    HubScaffold(title = "Настройки") {
        if (entries.isEmpty()) item { HubEmptyText("Нет доступных настроек.") }
        items(entries, key = { it.title }) { entry ->
            HubCard(entry.title, entry.subtitle, painterResource(entry.icon), onClick = { navigator.navigate(entry.destination) })
        }
    }
}

package io.github.iampopovich.eculense.feature.settings

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import io.github.iampopovich.eculense.core.navigation.LanguageDestination
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

/** Settings tab: a hub where features add their own items (adapter, AI providers, …) plus the app language picker. */
@OptIn(KoinExperimentalAPI::class)
val settingsModule = module {
    navigation<SettingsDestination> { SettingsRoute() }
    navigation<LanguageDestination> { LanguageRoute() }
    single(named("settings")) { TopLevelEntry(SettingsDestination, R.string.settings_tab, R.drawable.ic_settings, order = 20) }
    // order 1: above the car profile (5) and the adapter (10).
    single(named("language-hub")) {
        HubEntry(Hub.SETTINGS, LanguageDestination, R.string.settings_language_title, R.string.settings_language_subtitle, R.drawable.ic_language, order = 1)
    }
}

@Composable
internal fun SettingsRoute() {
    val koin = getKoin()
    val entries = remember { koin.getAll<HubEntry>().forHub(Hub.SETTINGS) }
    val navigator = LocalNavigator.current
    HubScaffold(title = stringResource(R.string.settings_tab)) {
        if (entries.isEmpty()) item { HubEmptyText(stringResource(R.string.settings_empty)) }
        items(entries, key = { it.titleRes }) { entry ->
            HubCard(
                stringResource(entry.titleRes),
                stringResource(entry.subtitleRes),
                painterResource(entry.icon),
                onClick = { navigator.navigate(entry.destination) },
            )
        }
    }
}

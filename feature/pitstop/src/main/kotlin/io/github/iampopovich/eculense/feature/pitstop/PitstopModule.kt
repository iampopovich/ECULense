package io.github.iampopovich.eculense.feature.pitstop

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.iampopovich.eculense.core.navigation.AiChatDestination
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.navigation.PitstopDestination
import io.github.iampopovich.eculense.core.navigation.RouteHandler
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

/**
 * The Pit stop tab: a hub of helpers (codes, trip history, analysis…). Features register items themselves
 * via HubEntry(Hub.PITSTOP, …); the AI assistant is shown only if some feature declared RouteHandler(AiChatDestination).
 */
@OptIn(KoinExperimentalAPI::class)
val pitstopModule = module {
    navigation<PitstopDestination> { PitstopRoute() }
    single(named("pitstop")) { TopLevelEntry(PitstopDestination, R.string.pitstop_tab, R.drawable.ic_pitstop, order = 10) }
}

@Composable
internal fun PitstopRoute() {
    val koin = getKoin()
    val entries = remember { koin.getAll<HubEntry>().forHub(Hub.PITSTOP) }
    val hasAssistant = remember { koin.getAll<RouteHandler>().any { it.routeClass == AiChatDestination::class } }
    // The chat registers its own HubEntry — then a separate placeholder card isn't needed (one AI card).
    val assistantListed = entries.any { it.destination is AiChatDestination }
    val navigator = LocalNavigator.current
    HubScaffold(title = stringResource(R.string.pitstop_tab)) {
        if (entries.isEmpty()) {
            item { HubEmptyText(stringResource(R.string.pitstop_empty)) }
        }
        items(entries, key = { it.titleRes }) { entry ->
            HubCard(
                stringResource(entry.titleRes),
                stringResource(entry.subtitleRes),
                painterResource(entry.icon),
                onClick = { navigator.navigate(entry.destination) },
            )
        }
        if (!assistantListed) {
            item {
                if (hasAssistant) {
                    HubCard(
                        stringResource(R.string.pitstop_assistant_title),
                        stringResource(R.string.pitstop_assistant_subtitle),
                        painterResource(R.drawable.ic_assistant),
                        onClick = { navigator.navigate(AiChatDestination()) },
                    )
                } else {
                    HubCard(
                        stringResource(R.string.pitstop_assistant_later_title),
                        stringResource(R.string.pitstop_assistant_later_subtitle),
                        painterResource(R.drawable.ic_assistant),
                    )
                }
            }
        }
    }
}

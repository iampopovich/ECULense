package io.github.iampopovich.eculense.feature.pitstop

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
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
 * The «Пит-стоп» tab: a hub of helpers (codes, trip history, analysis…). Features register items themselves
 * via HubEntry(Hub.PITSTOP, …); the AI assistant is shown only if some feature declared RouteHandler(AiChatDestination).
 */
@OptIn(KoinExperimentalAPI::class)
val pitstopModule = module {
    navigation<PitstopDestination> { PitstopRoute() }
    single(named("pitstop")) { TopLevelEntry(PitstopDestination, "Пит-стоп", R.drawable.ic_pitstop, order = 10) }
}

@Composable
internal fun PitstopRoute() {
    val koin = getKoin()
    val entries = remember { koin.getAll<HubEntry>().forHub(Hub.PITSTOP) }
    val hasAssistant = remember { koin.getAll<RouteHandler>().any { it.routeClass == AiChatDestination::class } }
    // The chat registers its own HubEntry — then a separate placeholder card isn't needed (one AI card).
    val assistantListed = entries.any { it.destination is AiChatDestination }
    val navigator = LocalNavigator.current
    HubScaffold(title = "Пит-стоп") {
        if (entries.isEmpty()) {
            item { HubEmptyText("Здесь появятся помощники: расшифровка ошибок, история поездок и анализ данных.") }
        }
        items(entries, key = { it.title }) { entry ->
            HubCard(entry.title, entry.subtitle, painterResource(entry.icon), onClick = { navigator.navigate(entry.destination) })
        }
        if (!assistantListed) {
            item {
                if (hasAssistant) {
                    HubCard(
                        "ИИ-помощник",
                        "Спросить о машине и ошибках",
                        painterResource(R.drawable.ic_assistant),
                        onClick = { navigator.navigate(AiChatDestination()) },
                    )
                } else {
                    HubCard("ИИ-помощник появится позже", "Модуль ИИ пока не подключён", painterResource(R.drawable.ic_assistant))
                }
            }
        }
    }
}

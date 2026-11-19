package io.github.iampopovich.eculense.feature.aichat

import io.github.iampopovich.eculense.core.navigation.AiChatDestination
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import io.github.iampopovich.eculense.core.navigation.RouteHandler
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** Chat is an item in the «Пит-стоп» hub (not a tab); RouteHandler enables «Спросить ИИ» on errors. */
@OptIn(KoinExperimentalAPI::class)
val aiChatModule = module {
    viewModel { (prefill: String?) -> ChatViewModel(get(), get(), prefill) }
    navigation<AiChatDestination> { route -> ChatRoute(route.prefill) }
    single(named("aichat-hub")) {
        HubEntry(Hub.PITSTOP, AiChatDestination(), "ИИ-помощник", "Спросить о машине и ошибках", R.drawable.ic_ai, order = 30)
    }
    // Tells the errors screen and the hub: "chat exists, links may be shown".
    single(named("aichat-route")) { RouteHandler(AiChatDestination::class) }
}

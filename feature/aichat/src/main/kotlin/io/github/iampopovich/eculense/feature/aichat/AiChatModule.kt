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

/** Чат — пункт хаба «Пит-стоп» (не вкладка); RouteHandler включает «Спросить ИИ» у ошибок. */
@OptIn(KoinExperimentalAPI::class)
val aiChatModule = module {
    viewModel { (prefill: String?) -> ChatViewModel(get(), prefill) }
    navigation<AiChatDestination> { route -> ChatRoute(route.prefill) }
    single(named("aichat-hub")) {
        HubEntry(Hub.PITSTOP, AiChatDestination(), "ИИ-помощник", "Спросить о машине и ошибках", R.drawable.ic_ai, order = 30)
    }
    // Говорит экрану ошибок и хабу: «чат есть, ссылки показывать можно».
    single(named("aichat-route")) { RouteHandler(AiChatDestination::class) }
}

package io.github.iampopovich.eculense.feature.sessions

import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import io.github.iampopovich.eculense.core.navigation.SessionDetailDestination
import io.github.iampopovich.eculense.core.navigation.SessionsDestination
import org.koin.android.ext.koin.androidContext
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** Поездки — пункт хаба «Пит-стоп»; детали поездки открываются поверх списка. */
@OptIn(KoinExperimentalAPI::class)
val sessionsModule = module {
    viewModelOf(::SessionsViewModel)
    viewModel { (sessionId: Long) -> SessionDetailViewModel(sessionId, get(), androidContext()) }
    navigation<SessionsDestination> { SessionsRoute() }
    navigation<SessionDetailDestination> { route -> SessionDetailRoute(route.sessionId) }
    single(named("sessions-hub")) {
        HubEntry(Hub.PITSTOP, SessionsDestination, "Поездки", "Записи, графики и экспорт CSV", R.drawable.ic_sessions, order = 20)
    }
}

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

/** Trips are an item in the Pit stop hub; trip details open on top of the list. */
@OptIn(KoinExperimentalAPI::class)
val sessionsModule = module {
    viewModelOf(::SessionsViewModel)
    viewModel { (sessionId: Long) -> SessionDetailViewModel(sessionId, get(), androidContext(), get()) }
    navigation<SessionsDestination> { SessionsRoute() }
    navigation<SessionDetailDestination> { route -> SessionDetailRoute(route.sessionId) }
    single(named("sessions-hub")) {
        HubEntry(Hub.PITSTOP, SessionsDestination, R.string.sessions_hub_title, R.string.sessions_hub_subtitle, R.drawable.ic_sessions, order = 20)
    }
}

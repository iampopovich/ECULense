package io.github.iampopovich.eculense.feature.health

import io.github.iampopovich.eculense.core.data.HealthCheckRunner
import io.github.iampopovich.eculense.core.navigation.AiChatDestination
import io.github.iampopovich.eculense.core.navigation.HealthCheckDestination
import io.github.iampopovich.eculense.core.navigation.HealthReportDestination
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import io.github.iampopovich.eculense.core.navigation.RouteHandler
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/**
 * B6 «Проверка за минуту» on Pit-stop. «Ask the AI» appears only if some module declared RouteHandler(AiChatDestination)
 * (AI removed → no button). Removing this module removes only the hub item.
 */
@OptIn(KoinExperimentalAPI::class)
val healthModule = module {
    single { HealthReportExport(get(), getOrNull()) }
    viewModel { params ->
        val ai = getKoin().getAll<RouteHandler>().any { it.routeClass == AiChatDestination::class }
        HealthViewModel(get<HealthCheckRunner>(), get(), get(), get(), aiAvailable = ai, openCheckId = params.getOrNull<Long>())
    }
    navigation<HealthCheckDestination> { HealthRoute() }
    navigation<HealthReportDestination> { key -> HealthRoute(checkId = key.checkId) }
    single(named("health-route")) { RouteHandler(HealthCheckDestination::class) }
    single(named("health-hub")) {
        HubEntry(Hub.PITSTOP, HealthCheckDestination, R.string.health_hub_title, R.string.health_hub_subtitle, R.drawable.ic_health, order = 9)
    }
}

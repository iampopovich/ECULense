package io.github.iampopovich.eculense.feature.analysis

import io.github.iampopovich.eculense.core.navigation.AnalysisDestination
import io.github.iampopovich.eculense.core.navigation.FindingDestination
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** «Анализ» — пункт хаба «Пит-стоп» сразу после «Ошибок». Работает без ИИ; «Спросить ИИ» — только если чат подключён. */
@OptIn(KoinExperimentalAPI::class)
val analysisModule = module {
    viewModel { AnalysisViewModel(get(), get()) }
    viewModel { (findingId: Long) -> FindingViewModel(findingId, get(), getAll()) }
    navigation<AnalysisDestination> { AnalysisRoute() }
    navigation<FindingDestination> { route -> FindingRoute(route.findingId) }
    single(named("analysis-hub")) {
        HubEntry(Hub.PITSTOP, AnalysisDestination, "Анализ", "Находки по записям и кодам ошибок", R.drawable.ic_analysis, order = 15)
    }
}

package io.github.iampopovich.eculense.feature.analysis

import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.dtcdb.DtcDescriptions
import io.github.iampopovich.eculense.core.navigation.AnalysisDestination
import io.github.iampopovich.eculense.core.navigation.FindingDestination
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** «Analysis» — item of the «Pit stop» hub right after «Trouble codes». Works without AI; «Ask AI» — only if chat is wired in. */
@OptIn(KoinExperimentalAPI::class)
val analysisModule = module {
    viewModel { AnalysisViewModel(get(), get()) }
    viewModel { (findingId: Long) ->
        val descriptions = get<DtcDescriptions>()
        val cars = get<CarRepository>()
        FindingViewModel(findingId, get(), getAll()) { codes ->
            descriptions.describeAll(codes, cars.observeActiveCar().first()?.brand).mapValues { (_, info) -> DtcNote(info.text, info.causes.take(2)) }
        }
    }
    navigation<AnalysisDestination> { AnalysisRoute() }
    navigation<FindingDestination> { route -> FindingRoute(route.findingId) }
    single(named("analysis-hub")) {
        HubEntry(Hub.PITSTOP, AnalysisDestination, R.string.analysis_hub_title, R.string.analysis_hub_subtitle, R.drawable.ic_analysis, order = 15)
    }
}

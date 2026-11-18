package io.github.iampopovich.eculense.feature.stats

import io.github.iampopovich.eculense.core.navigation.StatsDestination
import io.github.iampopovich.eculense.core.navigation.TopLevelEntry
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** «Статистика» — отдельная вкладка (решение пользователя): между «Панелью» (0) и «Пит-стопом» (10). */
@OptIn(KoinExperimentalAPI::class)
val statsModule = module {
    viewModel { StatsViewModel(get(), get(), get()) }
    navigation<StatsDestination> { StatsRoute() }
    single(named("stats")) { TopLevelEntry(StatsDestination, "Статистика", R.drawable.ic_stats, order = 5) }
}

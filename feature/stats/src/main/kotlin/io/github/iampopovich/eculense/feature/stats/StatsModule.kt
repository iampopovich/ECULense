package io.github.iampopovich.eculense.feature.stats

import io.github.iampopovich.eculense.core.data.FuelPrefs
import io.github.iampopovich.eculense.core.data.FuelRepository
import io.github.iampopovich.eculense.core.data.SessionRepository
import io.github.iampopovich.eculense.core.data.TripEventRepository
import io.github.iampopovich.eculense.core.navigation.StatsDestination
import io.github.iampopovich.eculense.core.navigation.TopLevelEntry
import io.github.iampopovich.eculense.core.navigation.TripDetailDestination
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** «Статистика» is a separate tab (user's decision): between «Панелью» (0) and «Пит-стопом» (10). Trip pages open on top. */
@OptIn(KoinExperimentalAPI::class)
val statsModule = module {
    viewModel { StatsViewModel(get(), get(), get(), fuel = getOrNull<FuelRepository>(), fuelPrefs = getOrNull<FuelPrefs>()) }
    viewModel { (tripId: Long) -> TripDetailViewModel(tripId, get(), get(), get(), getOrNull<SessionRepository>(), getOrNull<TripEventRepository>()) }
    navigation<StatsDestination> { StatsRoute() }
    navigation<TripDetailDestination> { route -> TripDetailRoute(route.tripId) }
    single(named("stats")) { TopLevelEntry(StatsDestination, R.string.stats_tab, R.drawable.ic_stats, order = 5) }
}

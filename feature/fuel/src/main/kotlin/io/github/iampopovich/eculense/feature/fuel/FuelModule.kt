package io.github.iampopovich.eculense.feature.fuel

import io.github.iampopovich.eculense.core.data.MaintenanceRepository
import io.github.iampopovich.eculense.core.navigation.ExpensesDestination
import io.github.iampopovich.eculense.core.navigation.FuelLogDestination
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import io.github.iampopovich.eculense.core.navigation.RouteHandler
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/**
 * D1/D2: fuel log and expenses — Pit-stop items; Stats links to them through the RouteHandlers (no feature dependency).
 * Removable: without this module Stats simply shows no «Fill-ups»/«Expenses» actions.
 */
@OptIn(KoinExperimentalAPI::class)
val fuelModule = module {
    viewModel { FuelLogViewModel(get(), get(), get(), get(), getOrNull<MaintenanceRepository>()) }
    viewModel { ExpensesViewModel(get(), get(), get(), get(), getOrNull<MaintenanceRepository>()) }
    navigation<FuelLogDestination> { FuelLogRoute() }
    navigation<ExpensesDestination> { ExpensesRoute() }
    single(named("fuel-route")) { RouteHandler(FuelLogDestination::class) }
    single(named("expenses-route")) { RouteHandler(ExpensesDestination::class) }
    single(named("fuel-hub")) { HubEntry(Hub.PITSTOP, FuelLogDestination, R.string.fuel_hub_title, R.string.fuel_hub_subtitle, R.drawable.ic_fuel, order = 18) }
    single(named("expenses-hub")) {
        HubEntry(Hub.PITSTOP, ExpensesDestination, R.string.expense_hub_title, R.string.expense_hub_subtitle, R.drawable.ic_expenses, order = 19)
    }
}

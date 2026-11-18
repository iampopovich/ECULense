package io.github.iampopovich.eculense.feature.car

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import io.github.iampopovich.eculense.core.navigation.CarDestination
import io.github.iampopovich.eculense.core.navigation.CarEditDestination
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** «Автомобиль» — пункт хаба «Настройки»: профиль машины, выбор активной, статистика поездок, единицы. */
@OptIn(KoinExperimentalAPI::class)
val carModule = module {
    viewModel { CarViewModel(get(), get(), get()) }
    viewModel { (carId: Long) -> CarEditViewModel(carId, get()) }
    navigation<CarDestination> { CarRoute() }
    navigation<CarEditDestination> { route ->
        val back = LocalOnBackPressedDispatcherOwner.current
        CarEditRoute(route.carId, onClose = { back?.onBackPressedDispatcher?.onBackPressed() })
    }
    single(named("car-hub")) {
        HubEntry(Hub.SETTINGS, CarDestination, "Автомобиль", "Профиль, статистика поездок, единицы", R.drawable.ic_car, order = 5)
    }
}

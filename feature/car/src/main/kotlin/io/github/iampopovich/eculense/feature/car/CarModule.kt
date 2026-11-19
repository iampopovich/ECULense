package io.github.iampopovich.eculense.feature.car

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import io.github.iampopovich.eculense.core.navigation.CarDestination
import io.github.iampopovich.eculense.core.navigation.CarEditDestination
import io.github.iampopovich.eculense.core.navigation.Hub
import io.github.iampopovich.eculense.core.navigation.HubEntry
import io.github.iampopovich.eculense.core.vehicledb.VehicleRepository
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** «Автомобиль» is an item in the «Настройки» hub: car list, profile, active car selection, units. Statistics is the :feature:stats tab. */
@OptIn(KoinExperimentalAPI::class)
val carModule = module {
    viewModel { CarViewModel(get(), get()) }
    viewModel { (carId: Long) -> CarEditViewModel(carId, get(), catalog = getOrNull<VehicleRepository>()?.let(::RepositoryVehicleCatalog), packs = getOrNull()) }
    navigation<CarDestination> { CarRoute() }
    navigation<CarEditDestination> { route ->
        val back = LocalOnBackPressedDispatcherOwner.current
        CarEditRoute(route.carId, onClose = { back?.onBackPressedDispatcher?.onBackPressed() })
    }
    single(named("car-hub")) {
        HubEntry(Hub.SETTINGS, CarDestination, R.string.car_hub_title, R.string.car_hub_subtitle, R.drawable.ic_car, order = 5)
    }
}

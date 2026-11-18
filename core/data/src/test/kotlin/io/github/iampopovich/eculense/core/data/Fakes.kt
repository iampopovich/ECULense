package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.UnitsSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Машины в памяти; как в Room — первая машина «Мой автомобиль» создаётся сразу. */
internal class FakeCars(withDefault: Boolean = true) : CarRepository {
    val all = MutableStateFlow(if (withDefault) listOf(CarProfile(id = 1, name = CarProfile.DEFAULT_NAME)) else emptyList())
    var active: Long? = null
    private var nextId = 2L

    override fun observeCars(): Flow<List<CarProfile>> = all
    override fun observeActiveCar(): Flow<CarProfile?> = all.map { cars -> cars.firstOrNull { it.id == active } ?: cars.firstOrNull() }
    override suspend fun activeCarId(): Long? = all.value.firstOrNull { it.id == active }?.id ?: all.value.firstOrNull()?.id
    override suspend fun cars() = all.value
    override suspend fun byExternalId(externalId: String) = all.value.firstOrNull { it.externalId == externalId }
    override suspend fun save(car: CarProfile): Long {
        if (car.id == 0L) {
            val id = nextId++
            all.value = all.value + car.copy(id = id)
            return id
        }
        all.value = all.value.map { if (it.id == car.id) car else it }
        return car.id
    }
    override suspend fun delete(id: Long): Boolean {
        if (all.value.size <= 1) return false
        all.value = all.value.filterNot { it.id == id }
        return true
    }
    override suspend fun setActive(id: Long) {
        active = id
    }
}

internal class FakeTrips : TripRepository {
    val all = MutableStateFlow<List<Trip>>(emptyList())
    override fun observeTrips(carId: Long): Flow<List<Trip>> = all.map { list -> list.filter { it.carId == carId } }
    override suspend fun trips(carId: Long): List<Trip> = all.value.filter { it.carId == carId }
    override suspend fun addAll(trips: List<Trip>): Int {
        val known = all.value.mapNotNull { it.externalId }.toSet()
        val fresh = trips.filter { it.externalId == null || it.externalId !in known }
        all.value = all.value + fresh
        return fresh.size
    }
}

internal class FakeUnits : UnitsRepository {
    override val units = MutableStateFlow(UnitsSettings())
    override suspend fun save(units: UnitsSettings) {
        this.units.value = units
    }
}

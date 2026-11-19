package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.database.DtcDao
import io.github.iampopovich.eculense.core.database.DtcSnapshotEntity
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.UnitsSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory cars; as in Room, the first car «Мой автомобиль» is created right away. */
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
    private var nextId = 1L
    override fun observeTrips(carId: Long): Flow<List<Trip>> = all.map { list -> list.filter { it.carId == carId } }
    override suspend fun trips(carId: Long): List<Trip> = all.value.filter { it.carId == carId }
    override suspend fun addAll(trips: List<Trip>): Int {
        val known = all.value.mapNotNull { it.externalId }.toSet()
        val fresh = trips.filter { it.externalId == null || it.externalId !in known }
        all.value = all.value + fresh.map { it.copy(id = nextId++) }
        return fresh.size
    }
    override suspend fun mergeImported(trips: List<Trip>): TripMerge {
        var added = 0
        var updated = 0
        var same = 0
        for (t in trips) {
            val old = all.value.firstOrNull { it.externalId != null && it.externalId == t.externalId }
            when {
                old == null -> { all.value = all.value + t.copy(id = nextId++); added++ }
                old.toEntity().sameTotalsAs(t.toEntity()) -> same++
                else -> { all.value = all.value.map { if (it.id == old.id) t.copy(id = old.id, carId = old.carId) else it }; updated++ }
            }
        }
        return TripMerge(added, updated, same)
    }
}

internal class FakeUnits : UnitsRepository {
    override val units = MutableStateFlow(UnitsSettings())
    override suspend fun save(units: UnitsSettings) {
        this.units.value = units
    }
}

/** In-memory code snapshots; queries mirror the SQL in DtcDao. */
internal class FakeDtcDao(val items: MutableList<DtcSnapshotEntity> = mutableListOf()) : DtcDao {
    override suspend fun insert(items: List<DtcSnapshotEntity>) { this.items += items }
    override suspend fun forCar(carId: Long) = items.filter { it.carId == carId }.sortedBy { it.takenAtMs }
    override suspend fun countEvents(code: String, fromMs: Long, toMs: Long) = items.count { it.code == code && it.takenAtMs in fromMs..toMs }
    override suspend fun latestForCar(carId: Long?): List<DtcSnapshotEntity> {
        val mine = items.filter { it.carId == carId }
        val last = mine.maxOfOrNull { it.takenAtMs } ?: return emptyList()
        return mine.filter { it.takenAtMs == last }
    }
}

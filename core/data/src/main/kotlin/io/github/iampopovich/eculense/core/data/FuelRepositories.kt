package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.analytics.FuelCalibration
import io.github.iampopovich.eculense.core.analytics.FuelLog
import io.github.iampopovich.eculense.core.analytics.FullToFull
import io.github.iampopovich.eculense.core.analytics.RangeEstimate
import io.github.iampopovich.eculense.core.database.ExpenseDao
import io.github.iampopovich.eculense.core.database.ExpenseEntity
import io.github.iampopovich.eculense.core.database.FuelDao
import io.github.iampopovich.eculense.core.database.FuelFillUpEntity
import io.github.iampopovich.eculense.core.database.OdometerDao
import io.github.iampopovich.eculense.core.database.OdometerEntity
import io.github.iampopovich.eculense.core.database.OdometerSyncDao
import io.github.iampopovich.eculense.core.model.Expense
import io.github.iampopovich.eculense.core.model.ExpenseCategory
import io.github.iampopovich.eculense.core.model.FuelFillUp
import io.github.iampopovich.eculense.core.model.FuelMethod
import io.github.iampopovich.eculense.core.model.OdometerSource
import io.github.iampopovich.eculense.core.model.Trip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** D1 fuel log of a car. Every fill-up with an odometer is also a measured odometer reading (odometer_log, FILL_UP). */
interface FuelRepository {
    fun observeFillUps(carId: Long): Flow<List<FuelFillUp>>
    suspend fun fillUps(carId: Long): List<FuelFillUp>
    /** id == 0 → new. Returns the id. */
    suspend fun save(fillUp: FuelFillUp): Long
    suspend fun delete(id: Long)
    /** Imported rows (externalId set): known ones are skipped. Each new row with an odometer writes its reading. */
    suspend fun import(rows: List<FuelFillUp>): FuelImportResult
}

data class FuelImportResult(val added: Int, val skipped: Int, val errors: Int = 0)

/** D1 setting: apply the calibration factor to the app's MAF estimates (off by default). Per car. */
interface FuelPrefs {
    fun mafCorrection(carId: Long): Flow<Boolean>
    suspend fun setMafCorrection(carId: Long, on: Boolean)
}

/** What the fuel log says about a car, ready for the Stats screen, the dashboard range and the AI tools. */
data class FuelInsights(
    val fillUps: List<FuelFillUp>,
    val intervals: List<FullToFull>,
    val calibration: FuelCalibration?,
    val correctionOn: Boolean,
) {
    /** The factor actually applied to MAF estimates (1.0 when off or unknown). */
    val appliedFactor: Double get() = if (correctionOn) calibration?.factor ?: 1.0 else 1.0

    /** Trips with MAF-estimated fuel multiplied by [appliedFactor]; other trips unchanged. */
    fun corrected(trips: List<Trip>): List<Trip> {
        val k = appliedFactor
        if (k == 1.0) return trips
        return trips.map { if (it.fuelMethod == FuelMethod.MAF_ESTIMATE) it.copy(fuelL = it.fuelL * k) else it }
    }

    companion object {
        fun of(fills: List<FuelFillUp>, trips: List<Trip>, correctionOn: Boolean): FuelInsights {
            val intervals = FuelLog.fullToFull(fills)
            return FuelInsights(fills, intervals, FuelCalibration.compute(intervals, trips), correctionOn)
        }
    }
}

fun observeFuelInsights(carId: Long, fuel: FuelRepository, trips: TripRepository, prefs: FuelPrefs): Flow<FuelInsights> =
    combine(fuel.observeFillUps(carId), trips.observeTrips(carId), prefs.mafCorrection(carId)) { f, t, on -> FuelInsights.of(f, t, on) }

/** D3: what the range of the active car is computed from; null when the tank capacity or a learned consumption is missing. */
data class RangeBasis(val tankL: Double, val lPer100: Double, val source: RangeEstimate.Source) {
    fun km(fuelLevelPercent: Double?): Double? = RangeEstimate.km(fuelLevelPercent, tankL, lPer100)
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun observeRangeBasis(cars: CarRepository, fuel: FuelRepository, trips: TripRepository, prefs: FuelPrefs): Flow<RangeBasis?> =
    cars.observeActiveCar().flatMapLatest { car ->
        val tank = car?.tankVolumeL
        if (car == null || tank == null || tank <= 0) flowOf(null)
        else combine(fuel.observeFillUps(car.id), trips.observeTrips(car.id), prefs.mafCorrection(car.id)) { f, t, on ->
            val i = FuelInsights.of(f, t, on)
            RangeEstimate.learnedLPer100(i.intervals, i.corrected(t))?.let { (l, s) -> RangeBasis(tank, l, s) }
        }
    }

internal class RoomFuelRepository(
    private val dao: FuelDao,
    private val odometer: OdometerDao,
    private val sync: OdometerSyncDao,
) : FuelRepository {
    override fun observeFillUps(carId: Long): Flow<List<FuelFillUp>> = dao.observeForCar(carId).map { l -> l.map { it.toModel() } }

    override suspend fun fillUps(carId: Long): List<FuelFillUp> = dao.forCar(carId).map { it.toModel() }

    override suspend fun save(fillUp: FuelFillUp): Long {
        val old = if (fillUp.id != 0L) dao.byId(fillUp.id) else null
        val logId = syncOdometer(fillUp, old?.odometerLogId)
        val entity = fillUp.toEntity().copy(odometerLogId = logId, externalId = fillUp.externalId ?: old?.externalId)
        return if (fillUp.id == 0L) dao.insert(entity) else fillUp.id.also { dao.update(entity) }
    }

    /** Writes, moves or removes the odometer reading of this fill-up; returns the row id to keep. */
    private suspend fun syncOdometer(f: FuelFillUp, logId: Long?): Long? {
        val km = f.odometerKm
        if (km == null) {
            logId?.let { odometer.delete(it) }
            return null
        }
        if (logId != null && sync.exists(logId) > 0) {
            sync.update(logId, km, f.atMs)
            return logId
        }
        return odometer.insert(OdometerEntity(carId = f.carId, km = km, atMs = f.atMs, source = OdometerSource.FILL_UP.name))
    }

    override suspend fun delete(id: Long) {
        val old = dao.byId(id) ?: return
        old.odometerLogId?.let { odometer.delete(it) }
        dao.delete(id)
    }

    override suspend fun import(rows: List<FuelFillUp>): FuelImportResult {
        var added = 0
        for (chunk in rows.chunked(200)) {
            val ids = dao.insertAll(chunk.map { it.toEntity() })
            ids.forEachIndexed { i, id ->
                if (id <= 0) return@forEachIndexed
                added++
                val f = chunk[i]
                val km = f.odometerKm ?: return@forEachIndexed
                val log = odometer.insert(OdometerEntity(carId = f.carId, km = km, atMs = f.atMs, source = OdometerSource.FILL_UP.name))
                dao.byId(id)?.let { dao.update(it.copy(odometerLogId = log)) }
            }
        }
        return FuelImportResult(added = added, skipped = rows.size - added)
    }
}

/** D2 expenses. A SERVICE/REPAIR expense linked to a maintenance work marks that work done (the auto-close). */
interface ExpenseRepository {
    fun observeExpenses(carId: Long): Flow<List<Expense>>
    suspend fun expenses(carId: Long): List<Expense>
    suspend fun save(expense: Expense): Long
    suspend fun delete(id: Long)

    companion object {
        val CLOSES_MAINTENANCE = setOf(ExpenseCategory.SERVICE, ExpenseCategory.REPAIR)
    }
}

internal class RoomExpenseRepository(
    private val dao: ExpenseDao,
    private val maintenance: MaintenanceRepository,
) : ExpenseRepository {
    override fun observeExpenses(carId: Long): Flow<List<Expense>> = dao.observeForCar(carId).map { l -> l.map { it.toModel() } }

    override suspend fun expenses(carId: Long): List<Expense> = dao.forCar(carId).map { it.toModel() }

    override suspend fun save(expense: Expense): Long {
        val id = if (expense.id == 0L) dao.insert(expense.toEntity()) else expense.id.also { dao.update(expense.toEntity()) }
        autoClose(expense)
        return id
    }

    /**
     * Marks the linked work done at the expense's date and odometer (the estimate when no odometer was typed). An older
     * expense never moves «last done» backwards: a later mark stays.
     */
    private suspend fun autoClose(e: Expense) {
        val itemId = e.maintenanceItemId ?: return
        if (e.category !in ExpenseRepository.CLOSES_MAINTENANCE) return
        val item = maintenance.items(e.carId).firstOrNull { it.id == itemId } ?: return
        val last = item.lastDoneAtMs
        if (last != null && last > e.atMs) return
        val km = e.odometerKm ?: maintenance.estimate(e.carId)?.km?.toLong()
        maintenance.markDone(itemId, km, e.atMs)
    }

    override suspend fun delete(id: Long) = dao.delete(id)
}

internal fun FuelFillUpEntity.toModel() = FuelFillUp(
    id, carId, atMs, odometerKm, litres, pricePerL, total, fullTank, missedPrevious, station, note, externalId,
)

internal fun FuelFillUp.toEntity() = FuelFillUpEntity(
    id = id, carId = carId, atMs = atMs, odometerKm = odometerKm, litres = litres, pricePerL = pricePerL, total = total,
    fullTank = fullTank, missedPrevious = missedPrevious, station = station, note = note, externalId = externalId,
)

internal fun ExpenseEntity.toModel() = Expense(
    id, carId, atMs, ExpenseCategory.entries.firstOrNull { it.name == category } ?: ExpenseCategory.OTHER, amount, odometerKm, note, maintenanceItemId,
)

internal fun Expense.toEntity() = ExpenseEntity(id, carId, atMs, category.name, amount, odometerKm, note, maintenanceItemId)

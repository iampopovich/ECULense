package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.database.ExpenseDao
import io.github.iampopovich.eculense.core.database.ExpenseEntity
import io.github.iampopovich.eculense.core.database.FuelDao
import io.github.iampopovich.eculense.core.database.FuelFillUpEntity
import io.github.iampopovich.eculense.core.database.MaintenanceItemEntity
import io.github.iampopovich.eculense.core.database.OdometerSyncDao
import io.github.iampopovich.eculense.core.model.Expense
import io.github.iampopovich.eculense.core.model.ExpenseCategory
import io.github.iampopovich.eculense.core.model.FuelFillUp
import io.github.iampopovich.eculense.core.model.FuelMethod
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

internal class FakeFuelDao : FuelDao {
    val rows = MutableStateFlow<List<FuelFillUpEntity>>(emptyList())
    private var next = 1L
    override fun observeForCar(carId: Long): Flow<List<FuelFillUpEntity>> = rows.map { l -> l.filter { it.carId == carId }.sortedByDescending { it.atMs } }
    override suspend fun forCar(carId: Long) = rows.value.filter { it.carId == carId }.sortedByDescending { it.atMs }
    override suspend fun byId(id: Long) = rows.value.firstOrNull { it.id == id }
    override suspend fun insert(item: FuelFillUpEntity): Long = next++.also { rows.value = rows.value + item.copy(id = it) }
    override suspend fun insertAll(items: List<FuelFillUpEntity>): List<Long> = items.map { e ->
        if (e.externalId != null && rows.value.any { it.externalId == e.externalId }) -1L else insert(e)
    }
    override suspend fun update(item: FuelFillUpEntity) { rows.value = rows.value.map { if (it.id == item.id) item else it } }
    override suspend fun delete(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
    override suspend fun count() = rows.value.size
}

internal class FakeOdometerSync(private val odo: FakeOdometerDao) : OdometerSyncDao {
    override suspend fun update(id: Long, km: Long, atMs: Long): Int {
        odo.rows.value = odo.rows.value.map { if (it.id == id) it.copy(km = km, atMs = atMs) else it }
        return 1
    }
    override suspend fun exists(id: Long) = odo.rows.value.count { it.id == id }
}

internal class FakeExpenseDao : ExpenseDao {
    val rows = MutableStateFlow<List<ExpenseEntity>>(emptyList())
    private var next = 1L
    override fun observeForCar(carId: Long): Flow<List<ExpenseEntity>> = rows.map { l -> l.filter { it.carId == carId } }
    override suspend fun forCar(carId: Long) = rows.value.filter { it.carId == carId }
    override suspend fun byId(id: Long) = rows.value.firstOrNull { it.id == id }
    override suspend fun insert(item: ExpenseEntity): Long = next++.also { rows.value = rows.value + item.copy(id = it) }
    override suspend fun update(item: ExpenseEntity) { rows.value = rows.value.map { if (it.id == item.id) item else it } }
    override suspend fun delete(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
}

class FuelAndExpensesTest {
    private val zone = ZoneOffset.UTC
    private fun ms(date: String) = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `a fill-up writes moves and removes its odometer reading`() = runTest {
        val odo = FakeOdometerDao()
        val repo = RoomFuelRepository(FakeFuelDao(), odo, FakeOdometerSync(odo))
        val f = FuelFillUp(carId = 1, atMs = ms("2026-01-10"), odometerKm = 12_000, litres = 40.0, pricePerL = 50.0, total = 2000.0)
        val id = repo.save(f)
        assertEquals(listOf(12_000L to "FILL_UP"), odo.rows.value.map { it.km to it.source })
        repo.save(f.copy(id = id, odometerKm = 12_050))
        assertEquals(listOf(12_050L), odo.rows.value.map { it.km })
        repo.save(f.copy(id = id, odometerKm = null))
        assertTrue(odo.rows.value.isEmpty())
        repo.save(f.copy(id = id))
        repo.delete(id)
        assertTrue(odo.rows.value.isEmpty())
        assertTrue(repo.fillUps(1).isEmpty())
    }

    @Test
    fun `a fill-up reading is the base of the odometer estimate`() = runTest {
        val odo = FakeOdometerDao()
        val trips = FakeTrips()
        val maintenance = RoomMaintenanceRepository(FakeMaintenanceDao(), odo, trips, FakeSeedFlags())
        RoomFuelRepository(FakeFuelDao(), odo, FakeOdometerSync(odo))
            .save(FuelFillUp(carId = 1, atMs = ms("2026-01-10"), odometerKm = 12_000, litres = 40.0, pricePerL = null, total = null))
        trips.addAll(listOf(Trip(carId = 1, startedAtMs = ms("2026-01-11"), endedAtMs = ms("2026-01-11") + 1, distanceKm = 25.0, fuelL = 2.0,
            fuelPricePerL = null, drivingMs = 1, standingMs = 0, source = TripSource.LIVE)))
        assertEquals(12_025.0, maintenance.estimate(1)!!.km, 1e-9)
    }

    @Test
    fun `csv import skips rows it already brought in and writes their readings`() = runTest {
        val odo = FakeOdometerDao()
        val repo = RoomFuelRepository(FakeFuelDao(), odo, FakeOdometerSync(odo))
        val rows = FuelCsv.parse(FUELIO_SAMPLE, carId = 1, zone = zone).rows
        assertEquals(FuelImportResult(added = 3, skipped = 0), repo.import(rows))
        assertEquals(FuelImportResult(added = 0, skipped = 3), repo.import(rows))
        assertEquals(3, odo.rows.value.size)
    }

    @Test
    fun `fuelio log section is read with units missed flags and the second tank as partial`() {
        val r = FuelCsv.parse(FUELIO_SAMPLE, carId = 1, zone = zone)
        assertEquals(FuelCsvFormat.FUELIO, r.format)
        assertEquals(1, r.badLines)
        val (a, b, c) = r.rows
        assertEquals(ms("2026-01-05") + 12 * 3_600_000L, a.atMs)
        assertEquals(10_000L, a.odometerKm)
        assertEquals(40.0, a.litres, 1e-9)
        assertEquals(2000.0, a.total!!, 1e-9)
        assertEquals(50.0, a.pricePerL!!, 1e-9)
        assertTrue(a.fullTank)
        assertEquals("Test station", a.station)
        // total missing: price × litres
        assertEquals(1050.0, b.total!!, 1e-9)
        assertFalse(b.fullTank)
        assertTrue(c.missedPrevious)
        assertFalse("second tank never closes an interval", c.fullTank)
        // miles and US gallons are converted
        val us = FuelCsv.parse(FUELIO_SAMPLE.replace("Odo (km)", "Odo (mi)").replace("Fuel (litres)", "Fuel (us gallons)"), 1, zone).rows.first()
        assertEquals(16_093L, us.odometerKm)
        assertEquals(151.416, us.litres, 1e-3)
    }

    @Test
    fun `generic csv is recognized by its column names`() {
        val csv = "Date;Odometer;Litres;Price;Full\n2026-02-01;20 000;35,5;52,0;yes\n02.02.2026;20 400;10;;no\nbad;row;x;;\n"
        val r = FuelCsv.parse(csv, carId = 2, zone = zone)
        assertEquals(FuelCsvFormat.GENERIC, r.format)
        assertEquals(2, r.rows.size)
        assertEquals(1, r.badLines)
        assertEquals(20_000L, r.rows[0].odometerKm)
        assertEquals(35.5 * 52.0, r.rows[0].total!!, 1e-9)
        assertFalse(r.rows[1].fullTank)
        assertNull(r.rows[1].total)
        assertEquals(FuelCsvFormat.UNKNOWN, FuelCsv.parse("a,b\n1,2", 1, zone).format)
    }

    private fun maintenanceWith(lastDoneAtMs: Long? = null): Pair<RoomMaintenanceRepository, FakeMaintenanceDao> {
        val dao = FakeMaintenanceDao()
        val odo = FakeOdometerDao()
        val repo = RoomMaintenanceRepository(dao, odo, FakeTrips(), FakeSeedFlags())
        dao.rows.value = listOf(MaintenanceItemEntity(id = 7, carId = 1, title = "Oil", intervalKm = 10_000, intervalMonths = 12, lastDoneKm = null, lastDoneAtMs = lastDoneAtMs, note = null))
        return repo to dao
    }

    @Test
    fun `a service expense linked to a work marks it done at its date and odometer`() = runTest {
        val (maintenance, dao) = maintenanceWith()
        val repo = RoomExpenseRepository(FakeExpenseDao(), maintenance)
        repo.save(Expense(carId = 1, atMs = ms("2026-03-01"), category = ExpenseCategory.SERVICE, amount = 3500.0, odometerKm = 21_000, maintenanceItemId = 7))
        assertEquals(21_000L, dao.rows.value.single().lastDoneKm)
        assertEquals(ms("2026-03-01"), dao.rows.value.single().lastDoneAtMs)
        assertEquals(1, repo.expenses(1).size)
    }

    @Test
    fun `other categories and older expenses never close or move a work back`() = runTest {
        val (maintenance, dao) = maintenanceWith(lastDoneAtMs = ms("2026-05-01"))
        val repo = RoomExpenseRepository(FakeExpenseDao(), maintenance)
        repo.save(Expense(carId = 1, atMs = ms("2026-06-01"), category = ExpenseCategory.PARTS, amount = 900.0, maintenanceItemId = 7))
        repo.save(Expense(carId = 1, atMs = ms("2026-03-01"), category = ExpenseCategory.REPAIR, amount = 900.0, maintenanceItemId = 7))
        assertEquals(ms("2026-05-01"), dao.rows.value.single().lastDoneAtMs)
    }

    @Test
    fun `insights apply the calibration factor to MAF trips only when switched on`() {
        val day = 86_400_000L
        val fills = listOf(
            FuelFillUp(carId = 1, atMs = 0, odometerKm = 1000, litres = 40.0, pricePerL = null, total = null),
            FuelFillUp(carId = 1, atMs = 10 * day, odometerKm = 1500, litres = 50.0, pricePerL = null, total = null),
        )
        fun trip(d: Int, method: FuelMethod?) = Trip(carId = 1, startedAtMs = d * day + 1, endedAtMs = d * day + 2, distanceKm = 200.0, fuelL = 17.6,
            fuelPricePerL = null, drivingMs = 1, standingMs = 0, source = TripSource.LIVE, fuelMethod = method)
        val trips = listOf(trip(2, FuelMethod.MAF_ESTIMATE), trip(5, FuelMethod.MAF_ESTIMATE), trip(6, null))
        val off = FuelInsights.of(fills, trips, correctionOn = false)
        assertEquals(1.0, off.appliedFactor, 0.0)
        assertEquals(trips, off.corrected(trips))
        val on = FuelInsights.of(fills, trips, correctionOn = true)
        val fixed = on.corrected(trips)
        assertEquals(20.0, fixed[0].fuelL, 1e-9)
        assertEquals(17.6, fixed[2].fuelL, 1e-9)
    }

    private companion object {
        // Synthetic Fuelio export (layout of Fuelio's own CSV sample); neutral values only.
        val FUELIO_SAMPLE = """
            "## Vehicle"
            "Name","Description","DistUnit","FuelUnit","ConsumptionUnit","ImportCSVDateFormat","VIN","Insurance","Plate","Make","Model","Year","TankCount","Tank1Type","Tank2Type","Active","Tank1Capacity","Tank2Capacity"
            "Test car","","0","0","0","yyyy-MM-dd","","","","Test","Car","2005","2","100","200","1","60.0","40.0"
            "## Log"
            "Data","Odo (km)","Fuel (litres)","Full","Price (optional)","l/100km (optional)","latitude (optional)","longitude (optional)","City (optional)","Notes (optional)","Missed","TankNumber","FuelType","VolumePrice","StationID (optional)","ExcludeDistance","UniqueId","TankCalc"
            "2026-01-05","10000","40.00","1","2000.00","0.0",,,"Test station","","0","1","110","50.00","0","0.0","1","0.0"
            "2026-01-12 18:30","10300","21.0","0","","0.0",,,"","note","0","1","110","50.00","0","0.0","2","0.0"
            "not a date","10500","20.0","1","1000","0.0",,,"","","0","1","110","50.00","0","0.0","3","0.0"
            "2026-01-20","10600","15.0","1","750","0.0",,,"","","1","2","200","50.00","0","0.0","4","0.0"
            "## CostCategories"
            "CostTypeID","Name","priority","color"
            "1","Service","0","#000000"
        """.trimIndent()
    }
}

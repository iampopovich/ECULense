package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.model.Expense
import io.github.iampopovich.eculense.core.model.ExpenseCategory
import io.github.iampopovich.eculense.core.model.FillUpMath
import io.github.iampopovich.eculense.core.model.FuelFillUp
import io.github.iampopovich.eculense.core.model.FuelMethod
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneOffset

class FuelLogTest {
    private val day = 86_400_000L
    private fun fill(d: Int, km: Long?, l: Double, full: Boolean = true, missed: Boolean = false, price: Double? = 50.0) =
        FuelFillUp(carId = 1, atMs = d * day, odometerKm = km, litres = l, pricePerL = price, total = null, fullTank = full, missedPrevious = missed)

    @Test
    fun `full to full sums partial fills and the closing full tank`() {
        val r = FuelLog.fullToFull(listOf(fill(0, 1000, 40.0), fill(3, 1200, 10.0, full = false), fill(6, 1500, 30.0)))
        assertEquals(1, r.size)
        assertEquals(500L, r.single().distanceKm)
        assertEquals(40.0, r.single().litres, 1e-9)
        assertEquals(8.0, r.single().lPer100Km, 1e-9)
        assertEquals(2, r.single().fillUps)
    }

    @Test
    fun `a missed fill breaks the chain and a full one marked missed opens a new interval`() {
        val r = FuelLog.fullToFull(
            listOf(fill(0, 1000, 40.0), fill(2, 1300, 20.0, missed = true), fill(4, 1600, 24.0), fill(5, 1700, 5.0, full = false, missed = true), fill(6, 1900, 25.0)),
        )
        // 1000 → 1300 dropped (missed), 1300 → 1600 = 24 L / 300 km; 1600 → 1900 broken by the missed partial
        assertEquals(listOf(1300L to 1600L), r.map { it.fromKm to it.toKm })
        assertEquals(8.0, r.single().lPer100Km, 1e-9)
    }

    @Test
    fun `fills before the first full tank and a full fill without odometer don't make intervals`() {
        val r = FuelLog.fullToFull(listOf(fill(0, 900, 5.0, full = false), fill(1, 1000, 40.0), fill(2, null, 30.0), fill(3, 1400, 30.0)))
        assertEquals(emptyList<FullToFull>(), r)
        // odometer typo (going backwards) is skipped, the next interval still counts
        val typo = FuelLog.fullToFull(listOf(fill(0, 1000, 40.0), fill(1, 900, 30.0), fill(2, 1300, 32.0)))
        assertEquals(listOf(900L to 1300L), typo.map { it.fromKm to it.toKm })
    }

    @Test
    fun `learned consumption is the median of the last intervals`() {
        val fills = listOf(fill(0, 0, 1.0), fill(1, 100, 8.0), fill(2, 200, 12.0), fill(3, 300, 9.0))
        assertEquals(9.0, FuelLog.learnedLPer100(FuelLog.fullToFull(fills))!!, 1e-9)
        assertNull(FuelLog.learnedLPer100(emptyList()))
    }

    @Test
    fun `third number of a fill-up is computed and the receipt total wins`() {
        assertEquals(Triple(40.0, 50.0, 2000.0), FillUpMath.complete(40.0, 50.0, null))
        assertEquals(Triple(40.0, 50.0, 2000.0), FillUpMath.complete(null, 50.0, 2000.0))
        assertEquals(50.5, FillUpMath.complete(40.0, 50.0, 2020.0)!!.second, 1e-9)
        assertNull(FillUpMath.complete(40.0, null, 0.0))
    }

    @Test
    fun `period summary uses the receipt total and intervals closed in the period`() {
        val fills = listOf(fill(0, 1000, 40.0), fill(10, 1500, 40.0, price = 60.0).copy(total = 2500.0))
        val s = FuelPeriodSummary.of(fills, FuelLog.fullToFull(fills), 5 * day, 20 * day)
        assertEquals(1, s.fillUps)
        assertEquals(2500.0, s.spend, 1e-9)
        assertEquals(8.0, s.lPer100Km!!, 1e-9)
    }

    private fun trip(d: Int, km: Double, l: Double, method: FuelMethod? = FuelMethod.MAF_ESTIMATE) = Trip(
        carId = 1, startedAtMs = d * day + 1000, endedAtMs = d * day + 3_600_000, distanceKm = km, fuelL = l, fuelPricePerL = null,
        drivingMs = 1, standingMs = 0, source = TripSource.LIVE, fuelMethod = method,
    )

    @Test
    fun `calibration compares the estimate with filled litres over the recorded distance`() {
        val intervals = FuelLog.fullToFull(listOf(fill(0, 1000, 40.0), fill(10, 1500, 50.0))) // 10 L/100 km
        val c = FuelCalibration.compute(intervals, listOf(trip(2, 200.0, 17.6), trip(5, 200.0, 17.6), trip(12, 300.0, 1.0)))!!
        assertEquals(400.0, c.recordedKm, 1e-9)
        assertEquals(8.8, c.estimatedLPer100, 1e-9)
        assertEquals(-12.0, c.deviationPercent, 1e-9)
        assertEquals(10.0 / 8.8, c.factor, 1e-9)
    }

    @Test
    fun `calibration needs enough recorded coverage and a sane factor`() {
        val intervals = FuelLog.fullToFull(listOf(fill(0, 1000, 40.0), fill(10, 1500, 50.0)))
        // 100 km of 500 recorded: below half the interval
        assertNull(FuelCalibration.compute(intervals, listOf(trip(2, 100.0, 9.0))))
        // Car Scanner trips (no own method) are not the app's estimate
        assertNull(FuelCalibration.compute(intervals, listOf(trip(2, 400.0, 35.0, method = null))))
        // factor 5: broken input
        assertNull(FuelCalibration.compute(intervals, listOf(trip(2, 400.0, 8.0))))
    }

    @Test
    fun `range from fuel level tank and learned consumption`() {
        assertEquals(250.0, RangeEstimate.km(50.0, 50.0, 10.0)!!, 1e-9)
        assertNull(RangeEstimate.km(null, 50.0, 10.0))
        assertNull(RangeEstimate.km(50.0, null, 10.0))
        assertNull(RangeEstimate.km(50.0, 50.0, 0.5))
        val trips = listOf(trip(1, 10.0, 1.0), trip(2, 20.0, 2.4), trip(3, 1.0, 0.5), trip(4, 30.0, 3.3))
        assertEquals(11.0 to RangeEstimate.Source.TRIPS, RangeEstimate.learnedLPer100(emptyList(), trips))
        val intervals = FuelLog.fullToFull(listOf(fill(0, 0, 1.0), fill(1, 100, 8.0)))
        assertEquals(8.0 to RangeEstimate.Source.FUEL_LOG, RangeEstimate.learnedLPer100(intervals, trips))
    }

    @Test
    fun `cost summary by category per day month and km`() {
        val expenses = listOf(
            Expense(carId = 1, atMs = 0, category = ExpenseCategory.SERVICE, amount = 3000.0),
            Expense(carId = 1, atMs = 29 * day, category = ExpenseCategory.WASH, amount = 500.0),
        )
        val fills = listOf(fill(0, 1000, 40.0)) // 2000
        val s = CostSummary.of(expenses, fills, Long.MIN_VALUE, 30 * day, distanceKm = 1100.0, zone = ZoneOffset.UTC)
        assertEquals(5500.0, s.total, 1e-9)
        assertEquals(2000.0, s.fuel, 1e-9)
        assertEquals(3000.0, s.byCategory[ExpenseCategory.SERVICE]!!, 1e-9)
        assertEquals(30L, s.days)
        assertEquals(5500.0 / 30, s.perDay!!, 1e-9)
        assertEquals(5.0, s.perKm!!, 1e-9)
        assertEquals(listOf(YearMonth.of(1970, 1)), s.byMonth.map { it.first })
        assertNull(CostSummary.of(emptyList(), emptyList(), Long.MIN_VALUE, 30 * day, null).perDay)
    }
}

package io.github.iampopovich.eculense.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneOffset

class TripStatsTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun trip(startedAtMs: Long, km: Double, l: Double, price: Double? = 60.0, drive: Long = 600_000, stand: Long = 60_000) =
        Trip(carId = 1, startedAtMs = startedAtMs, endedAtMs = startedAtMs + drive + stand, distanceKm = km, fuelL = l,
            fuelPricePerL = price, drivingMs = drive, standingMs = stand, source = TripSource.IMPORT_CARSCANNER)

    @Test
    fun `totals and average consumption over all trips`() {
        val stats = TripStats.of(listOf(trip(now, 10.0, 1.2), trip(now, 30.0, 3.0, price = null), trip(now, 0.0, 0.1)))
        assertEquals(3, stats.trips)
        assertEquals(40.0, stats.distanceKm, 1e-9)
        assertEquals(4.3, stats.fuelL, 1e-9)
        assertEquals(10.75, stats.avgLPer100Km!!, 1e-9)
        assertEquals(1_800_000L, stats.drivingMs)
        assertEquals(180_000L, stats.standingMs)
        assertEquals(1.3 * 60.0, stats.fuelCost, 1e-9)
    }

    @Test
    fun `no distance means no average instead of infinity`() {
        assertNull(TripStats.of(listOf(trip(now, 0.0, 0.5))).avgLPer100Km)
        assertNull(TripStats.EMPTY.avgLPer100Km)
    }

    @Test
    fun `period keeps only trips started within the last days`() {
        val trips = listOf(trip(now - 2 * day, 1.0, 0.1), trip(now - 20 * day, 2.0, 0.2), trip(now - 200 * day, 4.0, 0.4), trip(now - 400 * day, 8.0, 0.8))
        assertEquals(1.0, TripStatistics.forPeriod(trips, StatsPeriod.WEEK, now).distanceKm, 1e-9)
        assertEquals(3.0, TripStatistics.forPeriod(trips, StatsPeriod.MONTH, now).distanceKm, 1e-9)
        assertEquals(7.0, TripStatistics.forPeriod(trips, StatsPeriod.YEAR, now).distanceKm, 1e-9)
        assertEquals(15.0, TripStatistics.forPeriod(trips, StatsPeriod.ALL, now).distanceKm, 1e-9)
    }

    @Test
    fun `months are grouped in local time, newest first`() {
        // 2025-12-31T23:30Z = 2026-01-01 02:30 at UTC+3
        val newYearEve = 1_767_223_800_000L
        val months = TripStatistics.byMonth(listOf(trip(newYearEve, 5.0, 0.5), trip(newYearEve - 40 * day, 1.0, 0.1)), ZoneOffset.ofHours(3))
        assertEquals(listOf(YearMonth.of(2026, 1), YearMonth.of(2025, 11)), months.map { it.first })
    }

    @Test
    fun `untouched default car is detected and vin can be stripped`() {
        assertTrue(CarProfile(name = CarProfile.DEFAULT_NAME).isUntouchedDefault)
        assertFalse(CarProfile(name = CarProfile.DEFAULT_NAME, year = 2005).isUntouchedDefault)
        assertFalse(CarProfile(name = "Моя").isUntouchedDefault)
        assertNull(CarProfile(name = "x", vin = "TESTVIN0000000000").withoutVin().vin)
    }
}

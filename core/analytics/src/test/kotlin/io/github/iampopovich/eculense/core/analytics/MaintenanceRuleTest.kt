package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.model.FuelMethod
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import io.github.iampopovich.eculense.core.model.OdometerEntry
import io.github.iampopovich.eculense.core.model.OdometerSource
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class OdometerEstimatorTest {
    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L

    private fun trip(at: Long, km: Double) = Trip(carId = 1, startedAtMs = at, endedAtMs = at + 60_000, distanceKm = km, fuelL = 0.0,
        fuelPricePerL = null, drivingMs = 60_000, standingMs = 0, source = TripSource.LIVE, fuelMethod = FuelMethod.NOT_AVAILABLE)

    @Test
    fun `no manual entry means no estimate`() {
        assertNull(OdometerEstimator.estimate(emptyList(), listOf(trip(t0, 10.0))))
    }

    @Test
    fun `latest manual entry plus trips after it`() {
        val entries = listOf(
            OdometerEntry(carId = 1, km = 100_000, atMs = t0),
            OdometerEntry(carId = 1, km = 100_500, atMs = t0 + 10 * day),
            OdometerEntry(carId = 1, km = 999_999, atMs = t0 + 20 * day, source = OdometerSource.ESTIMATED),
        )
        val trips = listOf(trip(t0 + 5 * day, 40.0), trip(t0 + 11 * day, 12.5), trip(t0 + 12 * day, 7.5))
        val e = OdometerEstimator.estimate(entries, trips)!!
        assertEquals(100_500L, e.baseKm)
        assertEquals(t0 + 10 * day, e.baseAtMs)
        assertEquals(20.0, e.addedKm, 1e-9)
        assertEquals(100_520.0, e.km, 1e-9)
        assertTrue(e.estimated)
    }

    @Test
    fun `fresh manual entry without later trips is exact`() {
        val e = OdometerEstimator.estimate(listOf(OdometerEntry(carId = 1, km = 5_000, atMs = t0)), listOf(trip(t0 - day, 30.0)))!!
        assertEquals(5_000.0, e.km, 0.0)
        assertFalse(e.estimated)
    }
}

class MaintenanceRuleTest {
    private val zone = ZoneOffset.UTC
    private fun ms(date: String) = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
    private val now = ms("2026-06-01")

    private fun item(km: Int? = 10_000, months: Int? = 12, lastKm: Long? = 50_000, lastAt: Long? = ms("2026-01-01")) =
        MaintenanceItem(id = 1, carId = 1, title = "Масло", intervalKm = km, intervalMonths = months, lastDoneKm = lastKm, lastDoneAtMs = lastAt)

    @Test
    fun `well within both intervals is ok with remaining km and days`() {
        val d = MaintenanceRule.evaluate(item(), odometerKm = 54_000.0, nowMs = now, zone = zone)
        assertEquals(MaintenanceStatus.OK, d.status)
        assertEquals(6_000.0, d.kmLeft!!, 1e-9)
        assertEquals(60_000L, d.dueKm)
        assertEquals(ms("2027-01-01"), d.dueAtMs)
        assertEquals(214L, d.daysLeft)
    }

    @Test
    fun `within 10 percent of the km interval is soon`() {
        assertEquals(MaintenanceStatus.SOON, MaintenanceRule.evaluate(item(), 59_000.0, now, zone).status)
        assertEquals(MaintenanceStatus.OK, MaintenanceRule.evaluate(item(), 58_999.0, now, zone).status)
    }

    @Test
    fun `two weeks before the date is soon`() {
        val d = MaintenanceRule.evaluate(item(km = null, lastAt = ms("2025-06-10")), null, now, zone)
        assertEquals(9L, d.daysLeft)
        assertEquals(MaintenanceStatus.SOON, d.status)
    }

    @Test
    fun `past the km or the date is overdue`() {
        assertEquals(MaintenanceStatus.OVERDUE, MaintenanceRule.evaluate(item(), 60_300.0, now, zone).status)
        val byDate = MaintenanceRule.evaluate(item(lastAt = ms("2025-05-01")), 51_000.0, now, zone)
        assertEquals(MaintenanceStatus.OVERDUE, byDate.status)
        assertEquals(-31L, byDate.daysLeft)
    }

    @Test
    fun `never done or no intervals is unknown`() {
        assertEquals(MaintenanceStatus.UNKNOWN, MaintenanceRule.evaluate(item(lastKm = null, lastAt = null), 51_000.0, now, zone).status)
        assertEquals(MaintenanceStatus.UNKNOWN, MaintenanceRule.evaluate(item(km = null, months = null), 51_000.0, now, zone).status)
    }

    @Test
    fun `km interval without an odometer falls back to the date`() {
        val d = MaintenanceRule.evaluate(item(), odometerKm = null, nowMs = now, zone = zone)
        assertNull(d.kmLeft)
        assertEquals(MaintenanceStatus.OK, d.status)
    }

    @Test
    fun `due list puts overdue first then soon and leaves ok and unknown out`() {
        val items = listOf(
            item().copy(id = 1, title = "ok"),
            item(lastKm = 45_000).copy(id = 2, title = "soon"),
            item(lastKm = 40_000).copy(id = 3, title = "overdue"),
            item(lastKm = null, lastAt = null).copy(id = 4, title = "unknown"),
        )
        assertEquals(listOf("overdue", "soon"), MaintenanceRule.due(items, 54_500.0, now, zone).map { it.item.title })
        assertEquals(4, MaintenanceRule.evaluateAll(items, 54_500.0, now, zone).size)
    }
}

class MaintenanceTemplateTest {
    @Test
    fun `petrol template has the standard works`() {
        val items = MaintenanceTemplate.forCar(io.github.iampopovich.eculense.core.model.CarProfile(id = 7, name = "Тестовая"))
        val byTitle = items.associateBy { it.title }
        assertEquals(10_000, byTitle.getValue("Масло и масляный фильтр двигателя").intervalKm)
        assertEquals(12, byTitle.getValue("Масло и масляный фильтр двигателя").intervalMonths)
        assertEquals(20_000, byTitle.getValue("Воздушный фильтр").intervalKm)
        assertEquals(48, byTitle.getValue("Охлаждающая жидкость").intervalMonths)
        assertEquals(24, byTitle.getValue("Тормозная жидкость").intervalMonths)
        assertEquals(60_000, byTitle.getValue("Масло АКПП").intervalKm)
        assertTrue(byTitle.containsKey("Свечи зажигания"))
        assertTrue(byTitle.containsKey("Ремень/цепь ГРМ"))
        assertTrue(items.all { it.carId == 7L && it.lastDoneKm == null && it.lastDoneAtMs == null })
    }

    @Test
    fun `profile intervals override the template and manual gearbox has no atf`() {
        val car = io.github.iampopovich.eculense.core.model.CarProfile(
            id = 1, name = "Тестовая", gearbox = io.github.iampopovich.eculense.core.model.Gearbox.MANUAL,
            service = io.github.iampopovich.eculense.core.model.ServiceIntervals(oilKm = 7_500, sparkPlugsKm = 45_000, fuelFilterKm = 30_000),
        )
        val byTitle = MaintenanceTemplate.forCar(car).associateBy { it.title }
        assertEquals(7_500, byTitle.getValue("Масло и масляный фильтр двигателя").intervalKm)
        assertEquals(45_000, byTitle.getValue("Свечи зажигания").intervalKm)
        assertEquals(30_000, byTitle.getValue("Топливный фильтр").intervalKm)
        assertFalse(byTitle.containsKey("Масло АКПП"))
    }

    @Test
    fun `diesel has no spark plugs`() {
        val car = io.github.iampopovich.eculense.core.model.CarProfile(id = 1, name = "Тестовая", fuelType = io.github.iampopovich.eculense.core.model.FuelType.DIESEL)
        assertFalse(MaintenanceTemplate.forCar(car).any { it.title == "Свечи зажигания" })
    }
}

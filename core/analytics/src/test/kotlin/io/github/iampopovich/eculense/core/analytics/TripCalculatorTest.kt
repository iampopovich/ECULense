package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.analytics.SensorIds.FUEL_RATE
import io.github.iampopovich.eculense.core.analytics.SensorIds.MAF
import io.github.iampopovich.eculense.core.analytics.SensorIds.SPEED
import io.github.iampopovich.eculense.core.model.FuelMethod
import io.github.iampopovich.eculense.core.model.FuelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Синтетические ряды: скорость/MAF/расход с шагом в секундах, ожидания посчитаны вручную. */
class TripCalculatorTest {
    private data class P(val id: String, val v: Double, val tMs: Long)

    private fun series(id: String, fromSec: Int, toSec: Int, value: (Int) -> Double, stepSec: Int = 1) =
        (fromSec..toSec step stepSec).map { P(id, value(it), it * 1000L) }

    private fun compute(points: List<P>, fuel: FuelType? = FuelType.PETROL) =
        TripCalculator.compute(points.sortedBy { it.tMs }.map { Triple(it.id, it.v, it.tMs) }, fuel)

    @Test
    fun `constant 36 kmh for 100 s is one kilometre of driving`() {
        val m = compute(series(SPEED, 0, 100, { 36.0 }))!!
        assertEquals(1.0, m.distanceKm, 1e-9)
        assertEquals(100_000L, m.drivingMs)
        assertEquals(0L, m.standingMs)
        assertEquals(0L, m.startedAtMs)
        assertEquals(100_000L, m.endedAtMs)
    }

    @Test
    fun `trapezoids integrate a linear acceleration exactly`() {
        // 0 → 72 км/ч за 20 с: средняя 36 км/ч → 200 м
        val m = compute(series(SPEED, 0, 20, { it * 3.6 }))!!
        assertEquals(0.2, m.distanceKm, 1e-9)
    }

    @Test
    fun `gaps longer than 10 s are not integrated and not counted as time`() {
        val points = series(SPEED, 0, 50, { 36.0 }) + series(SPEED, 80, 130, { 36.0 })
        val m = compute(points)!!
        assertEquals(1.0, m.distanceKm, 1e-9)
        assertEquals(100_000L, m.drivingMs)
        assertEquals(0L, m.standingMs)
        assertEquals(130_000L, m.endedAtMs)
    }

    @Test
    fun `speed at or below 2 kmh is standing`() {
        val points = series(SPEED, 0, 30, { if (it <= 30) 0.0 else 0.0 }) + series(SPEED, 31, 90, { 36.0 })
        val m = compute(points)!!
        assertEquals(30_000L, m.standingMs)
        // интервал 30→31 с (0 → 36 км/ч) — средняя 18 км/ч, это уже движение
        assertEquals(60_000L, m.drivingMs)
    }

    @Test
    fun `fuel rate pid wins over maf`() {
        val points = series(SPEED, 0, 100, { 36.0 }) + series(FUEL_RATE, 0, 100, { 3.6 }) + series(MAF, 0, 100, { 50.0 })
        val m = compute(points)!!
        assertEquals(FuelMethod.OBD_FUEL_RATE, m.fuelMethod)
        assertEquals(0.1, m.fuelL, 1e-9)
    }

    @Test
    fun `maf estimate uses stoichiometric petrol and its density`() {
        val points = series(SPEED, 0, 1000, { 0.0 }, stepSec = 5) + series(MAF, 0, 1000, { 10.0 }, stepSec = 5)
        val m = compute(points)!!
        assertEquals(FuelMethod.MAF_ESTIMATE, m.fuelMethod)
        assertEquals(10.0 * 1000 / 14.7 / 745.0, m.fuelL, 1e-9)
        assertEquals(1_000_000L, m.standingMs)
    }

    @Test
    fun `unknown fuel type is treated as petrol`() {
        val points = series(SPEED, 0, 10, { 36.0 }) + series(MAF, 0, 10, { 10.0 })
        assertEquals(FuelMethod.MAF_ESTIMATE, compute(points, fuel = null)!!.fuelMethod)
    }

    @Test
    fun `diesel has no maf estimate`() {
        val points = series(SPEED, 0, 100, { 36.0 }) + series(MAF, 0, 100, { 20.0 })
        val m = compute(points, FuelType.DIESEL)!!
        assertEquals(FuelMethod.NOT_AVAILABLE, m.fuelMethod)
        assertEquals(0.0, m.fuelL, 0.0)
        assertEquals(1.0, m.distanceKm, 1e-9)
    }

    @Test
    fun `no fuel sensors means fuel is not available`() {
        assertEquals(FuelMethod.NOT_AVAILABLE, compute(series(SPEED, 0, 10, { 36.0 }))!!.fuelMethod)
    }

    @Test
    fun `fewer than two speed samples is not a trip`() {
        assertNull(compute(listOf(P(SPEED, 30.0, 0))))
        assertNull(compute(series(MAF, 0, 100, { 5.0 })))
    }
}

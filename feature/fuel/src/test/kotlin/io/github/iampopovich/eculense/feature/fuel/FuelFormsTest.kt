package io.github.iampopovich.eculense.feature.fuel

import io.github.iampopovich.eculense.core.model.ExpenseCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class FuelFormsTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDate.parse("2026-03-10").atTime(15, 0).toInstant(zone).toEpochMilli()

    @Test
    fun `new form is prefilled with today the odometer estimate and the car price`() {
        val f = FillUpForm.new(now, zone, odometerKm = 123_456.6, fuelPrice = 55.5)
        assertEquals("10.03.2026", f.date)
        assertEquals("123457", f.odometer)
        assertEquals("55.5", f.price)
        assertTrue(f.fullTank)
    }

    @Test
    fun `two of three numbers make a fill-up and the third is computed`() {
        val base = FillUpForm(date = "10.03.2026", odometer = "120 000")
        assertEquals(setOf(FillUpError.NEED_TWO), base.errors(zone, now))
        val byTotal = base.copy(litres = "40", total = "2 200").toFillUp(1, zone, now)!!
        assertEquals(55.0, byTotal.pricePerL!!, 1e-9)
        assertEquals(120_000L, byTotal.odometerKm)
        val byPrice = base.copy(price = "55,5", total = "2220").toFillUp(1, zone, now)!!
        assertEquals(40.0, byPrice.litres, 1e-9)
        assertNull(base.copy(litres = "-3", price = "50").toFillUp(1, zone, now))
    }

    @Test
    fun `future dates odometer garbage and absurd litres are refused`() {
        val ok = FillUpForm(date = "10.03.2026", litres = "40", price = "50")
        assertEquals(setOf(FillUpError.DATE), ok.copy(date = "11.03.2026").errors(zone, now))
        assertEquals(setOf(FillUpError.ODOMETER), ok.copy(odometer = "12a").errors(zone, now))
        assertEquals(setOf(FillUpError.LITRES), ok.copy(litres = "900").errors(zone, now))
        assertTrue(ok.copy(odometer = "").errors(zone, now).isEmpty())
    }

    @Test
    fun `expense form validates the amount and links works only for service and repair`() {
        val f = ExpenseForm.new(now, zone).copy(amount = "3 500", maintenanceItemId = 7)
        assertEquals(7L, f.toExpense(1, zone, now)!!.maintenanceItemId)
        assertNull(f.copy(category = ExpenseCategory.WASH).toExpense(1, zone, now)!!.maintenanceItemId)
        assertEquals(setOf(ExpenseError.AMOUNT), f.copy(amount = "0").errors(zone, now))
        assertEquals(3500.0, f.toExpense(1, zone, now)!!.amount, 0.0)
    }
}

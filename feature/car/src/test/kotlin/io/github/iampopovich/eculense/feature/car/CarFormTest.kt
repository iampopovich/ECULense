package io.github.iampopovich.eculense.feature.car

import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.FuelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarFormTest {
    private val year = 2026
    private val now = 1_800_000_000_000L

    @Test
    fun `name is required, everything else optional`() {
        assertEquals(setOf(CarField.NAME), CarForm().errors(year).keys)
        assertTrue(CarForm(name = "Моя").errors(year).isEmpty())
    }

    @Test
    fun `numbers are validated with ranges and comma decimals`() {
        val form = CarForm(name = "x", year = "1800", engineVolumeL = "2,5", cylinders = "abc", tankVolumeL = "70", odometerKm = "-5", fuelPrice = "0")
        assertEquals(setOf(CarField.YEAR, CarField.CYLINDERS, CarField.ODOMETER, CarField.FUEL_PRICE), form.errors(year).keys)
        assertNull(form.toProfile(null, now, year))
    }

    @Test
    fun `vin and currency formats`() {
        assertEquals(setOf(CarField.VIN), CarForm(name = "x", vin = "TOO-SHORT").errors(year).keys)
        // I, O, Q запрещены в VIN
        assertEquals(setOf(CarField.VIN), CarForm(name = "x", vin = "OOOOOOOOOOOOOOOOO").errors(year).keys)
        assertTrue(CarForm(name = "x", vin = "abcdefgh123456789", currency = "rub").errors(year).isEmpty())
        assertEquals(setOf(CarField.CURRENCY), CarForm(name = "x", currency = "рубли").errors(year).keys)
    }

    @Test
    fun `profile is built and normalised`() {
        val p = CarForm(
            name = "  Моя  ", brand = "Kia", year = "2005", engineVolumeL = "3,5", cylinders = "6", fuelType = FuelType.PETROL,
            odometerKm = "250 000", vin = "abcdefgh123456789", fuelPrice = "57,3", currency = "rub", oilKm = "10000",
        ).toProfile(null, now, year)!!
        assertEquals("Моя", p.name)
        assertEquals(2005, p.year)
        assertEquals(3.5, p.engineVolumeL!!, 1e-9)
        assertEquals(250_000L, p.odometerKm)
        assertEquals(now, p.odometerAtMs)
        assertEquals("ABCDEFGH123456789", p.vin)
        assertEquals("RUB", p.currency)
        assertEquals(57.3, p.fuelPrice!!, 1e-9)
        assertEquals(10_000, p.service.oilKm)
        assertNull(p.model)
    }

    @Test
    fun `odometer date changes only when odometer changes`() {
        val original = CarProfile(id = 3, name = "x", odometerKm = 1000, odometerAtMs = 42, externalId = "carscanner:1", diagProfile = "OBD-II")
        val same = CarForm.from(original).copy(name = "y").toProfile(original, now, year)!!
        assertEquals(42L, same.odometerAtMs)
        assertEquals("carscanner:1", same.externalId)
        assertEquals("OBD-II", same.diagProfile)
        assertEquals(3L, same.id)
        val changed = CarForm.from(original).copy(odometerKm = "1500").toProfile(original, now, year)!!
        assertEquals(now, changed.odometerAtMs)
    }

    @Test
    fun `form round-trips a profile`() {
        val car = CarProfile(id = 1, name = "x", engineVolumeL = 2.0, fuelPrice = 57.3, tankVolumeL = 70.0)
        val form = CarForm.from(car)
        assertEquals("2", form.engineVolumeL)
        assertEquals("57.3", form.fuelPrice)
        assertEquals(car, form.toProfile(car, now, year))
    }
}

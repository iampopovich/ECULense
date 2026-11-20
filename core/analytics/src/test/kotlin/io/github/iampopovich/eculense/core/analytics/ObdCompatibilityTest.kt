package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.model.FuelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** C12: «the car may predate mandatory OBD-II» by model year, fuel and the phone's country. */
class ObdCompatibilityTest {
    @Test
    fun `a 2005 petrol car in Russia gets no note`() {
        assertNull(ObdCompatibility.hint(2005, FuelType.PETROL, "RU"))
    }

    @Test
    fun `EU rule - petrol before 2001, diesel before 2004`() {
        assertEquals(CompatibilityHint(ObdRule.EU, lateAdoption = false), ObdCompatibility.hint(2000, FuelType.PETROL, "DE"))
        assertNull(ObdCompatibility.hint(2001, FuelType.PETROL, "DE"))
        assertEquals(CompatibilityHint(ObdRule.EU, lateAdoption = false), ObdCompatibility.hint(2003, FuelType.DIESEL, "FR"))
        assertNull(ObdCompatibility.hint(2004, FuelType.DIESEL, "NO"))
        assertNull(ObdCompatibility.hint(2002, FuelType.PETROL, "pl"))
    }

    @Test
    fun `RU, BY, KZ, UA use the EU rule with the late adoption note`() {
        listOf("RU", "BY", "KZ", "UA").forEach {
            assertEquals(CompatibilityHint(ObdRule.EU, lateAdoption = true), ObdCompatibility.hint(1999, FuelType.PETROL, it))
        }
        assertEquals(CompatibilityHint(ObdRule.EU, lateAdoption = true), ObdCompatibility.hint(2003, FuelType.DIESEL, "RU"))
    }

    @Test
    fun `US and CA - before 1996 for any fuel`() {
        assertEquals(CompatibilityHint(ObdRule.US, lateAdoption = false), ObdCompatibility.hint(1995, FuelType.DIESEL, "US"))
        assertNull(ObdCompatibility.hint(1996, FuelType.PETROL, "CA"))
    }

    @Test
    fun `other countries, unknown year and electric cars get no note`() {
        assertNull(ObdCompatibility.hint(1990, FuelType.PETROL, "JP"))
        assertNull(ObdCompatibility.hint(1990, FuelType.PETROL, ""))
        assertNull(ObdCompatibility.hint(null, FuelType.PETROL, "DE"))
        assertNull(ObdCompatibility.hint(1999, FuelType.ELECTRIC, "DE"))
    }

    @Test
    fun `unknown fuel uses the petrol year`() {
        assertEquals(CompatibilityHint(ObdRule.EU, lateAdoption = false), ObdCompatibility.hint(2000, null, "IT"))
        assertNull(ObdCompatibility.hint(2002, null, "IT"))
    }
}

package io.github.iampopovich.eculense.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsSettingsTest {
    private val imperial = UnitsSettings(metricDistance = false, celsius = false, litersPer100Km = false, liters = false, pressureKpa = false)

    @Test
    fun `metric settings leave values untouched`() {
        assertEquals(100.0 to "km/h", UnitsSettings().display(100.0, "km/h"))
        assertEquals(90.0 to "°C", UnitsSettings().display(90.0, "°C"))
        assertEquals(8.0 to "л/100 км", UnitsSettings().consumption(8.0))
    }

    @Test
    fun `imperial conversions`() {
        val (mph, unit) = imperial.display(100.0, "km/h")
        assertEquals("mph", unit)
        assertEquals(62.137, mph, 1e-3)
        assertEquals(194.0 to "°F", imperial.display(90.0, "°C"))
        assertEquals(14.504, imperial.display(100.0, "kPa").first, 1e-3)
        assertEquals(1500.0 to "rpm", imperial.display(1500.0, "rpm"))
        assertEquals(29.4, imperial.consumption(8.0).first, 0.01)
        assertEquals(10.0, imperial.volume(37.85411784).first, 1e-9)
        assertEquals(35.31, imperial.copy(usGallon = false).consumption(8.0).first, 0.01)
    }
}

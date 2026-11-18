package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.UnitsSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCsvTest {
    @Test
    fun `writes long format with header`() {
        val out = StringBuilder()
        SessionCsv.write(listOf(SensorReading("01.0C", 812.5, "rpm", 1000), SensorReading("01.0D", 0.0, "km/h", 1500)), out)
        assertEquals("timestamp_ms;sensor_id;value;unit\n1000;01.0C;812.5;rpm\n1500;01.0D;0.0;km/h\n", out.toString())
    }

    @Test
    fun `values follow the user's units`() {
        val out = StringBuilder()
        SessionCsv.write(listOf(SensorReading("01.05", 100.0, "°C", 1000)), out, UnitsSettings(celsius = false))
        assertEquals("timestamp_ms;sensor_id;value;unit\n1000;01.05;212.0;°F\n", out.toString())
    }
}

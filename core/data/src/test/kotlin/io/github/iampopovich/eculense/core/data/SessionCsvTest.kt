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

    @Test
    fun `streaming export writes every reading in time order with the same format`() = kotlinx.coroutines.test.runTest {
        val repo = FakeSessionRepository().apply {
            appended += SensorReading("01.0D", 0.0, "km/h", 1500)
            appended += SensorReading("01.0C", 812.5, "rpm", 1000)
        }
        val out = StringBuilder()
        assertEquals(2L, SessionCsv.write(repo, 1, out, UnitsSettings(metricDistance = false)))
        assertEquals("timestamp_ms;sensor_id;value;unit\n1000;01.0C;812.5;rpm\n1500;01.0D;0.0;mph\n", out.toString())
    }

    @Test
    fun `a new export deletes the previous files of the folder`() {
        val dir = kotlin.io.path.createTempDirectory("exports").toFile()
        java.io.File(dir, "ecu-session-1.csv").writeText("old")
        java.io.File(dir, "ecu-accel-2.csv").writeText("old")
        val f = ExportFiles.fresh(dir, "ecu-session-3.csv")
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        f.writeText("new")
        assertEquals(listOf("ecu-session-3.csv"), dir.list()!!.toList())
        dir.deleteRecursively()
    }
}

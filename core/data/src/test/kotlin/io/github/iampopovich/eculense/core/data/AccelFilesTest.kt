package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.analytics.CalibrationGap
import io.github.iampopovich.eculense.core.analytics.SpeedSample
import io.github.iampopovich.eculense.core.analytics.StyleThresholds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import kotlin.math.sin

class AccelFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val start = 1_700_000_000_000L

    private fun AccelFiles.writeStill(id: Long, seconds: Int, hz: Int = 50) {
        writer(id, AccelSource.LINEAR_ACCELERATION, start).use { w ->
            for (i in 0 until seconds * hz) {
                val t = start + i * 1000L / hz
                w.linear(t, 0.01f * sin(i.toDouble()).toFloat(), -0.02f, 0.03f)
                if (i % hz == 0) w.gravity(t, 0.1f, 0.2f, 9.79f)
            }
        }
    }

    @Test
    fun `round trip keeps samples within the 0_002 m_s2 resolution`() {
        val files = AccelFiles(tmp.newFolder("accel"))
        files.writer(7, AccelSource.ACCELEROMETER_MINUS_GRAVITY, start).use { w ->
            w.linear(start + 20, 1.2345f, -3.21f, 0f)
            w.gravity(start + 40, 0f, 0f, 9.80665f)
            w.linear(start + 60, 70f, -70f, 0.001f) // clamped to the short range
        }
        val r = files.read(7)!!
        assertEquals(AccelSource.ACCELEROMETER_MINUS_GRAVITY, r.source)
        assertEquals(2, r.linear.size)
        assertEquals(1, r.gravity.size)
        assertEquals(start + 20, r.linear[0].tMs)
        assertEquals(1.2345, r.linear[0].x, 0.001)
        assertEquals(-3.21, r.linear[0].y, 0.001)
        assertEquals(9.80665, r.gravity[0].z, 0.001)
        assertEquals(Short.MAX_VALUE / AccelFiles.SCALE, r.linear[1].x, 1e-9)
        assertEquals(Short.MIN_VALUE / AccelFiles.SCALE, r.linear[1].y, 1e-9)
    }

    @Test
    fun `one hour at 50 Hz stays under 3 MB`() {
        val files = AccelFiles(tmp.newFolder("accel"))
        files.writeStill(1, seconds = 3600)
        val bytes = files.file(1).length()
        assertEquals(AccelFiles.HEADER_BYTES + (3600L * 50 + 3600) * AccelFiles.RECORD_BYTES, bytes)
        assertTrue("$bytes bytes", bytes < 3_000_000)
    }

    @Test
    fun `a truncated last record is ignored`() {
        val files = AccelFiles(tmp.newFolder("accel"))
        files.writeStill(2, seconds = 1)
        RandomAccessFile(files.file(2), "rw").use { it.setLength(it.length() - 5) }
        val r = files.read(2)!!
        assertEquals(49, r.linear.size)
    }

    @Test
    fun `foreign files are rejected and orphans are swept`() {
        val dir = tmp.newFolder("accel")
        val files = AccelFiles(dir)
        dir.resolve("9.bin").writeText("not ours")
        assertNull(files.read(9))
        files.writeStill(1, 1)
        files.writeStill(2, 1)
        files.writeStill(3, 1)
        assertEquals(2, files.sweep(existing = setOf(1L), keep = 3L)) // 2 and the foreign 9
        assertEquals(listOf(1L, 3L), files.list().map { it.first })
        assertFalse(files.exists(2))
        files.delete(1)
        assertFalse(files.exists(1))
    }

    @Test
    fun `still phone on a demo recording reports not enough data and exports csv`() = runTest {
        val files = AccelFiles(tmp.newFolder("accel"))
        files.writeStill(5, seconds = 30)
        val speed = (0 until 30).map { SpeedSample(start + it * 1000L, 40.0) }
        val cal = AccelCalibrations(files, speed = { _, from, to -> speed.filter { it.tMs in from..to } }, carSessions = { listOf(5L) })
        val report = cal.report(5)!!
        assertEquals(1500, report.samples)
        // Constant speed: windows move but carry no variation, so no axis can be fitted.
        assertTrue(report.calibration.gap in setOf(CalibrationGap.NO_MOVEMENT, CalibrationGap.AXIS_NOT_FOUND))
        val csv = tmp.root.resolve("out/c.csv")
        val rows = cal.writeCsv(5, csv)
        val lines = csv.readLines()
        assertEquals(AccelCalibrations.CSV_HEADER, lines[0])
        assertEquals(rows + 1, lines.size)
        assertTrue(lines[10].split(',').size == 7)
        assertNull(cal.report(6))
    }

    @Test
    fun `style override falls back to the Geotab default`() = runTest {
        val prefs = InMemoryStylePrefs()
        assertEquals(StyleThresholds.HARSH_ACCEL_MS2, AccelCalibrations.harshOrDefault(prefs, 1), 1e-9)
        prefs.setHarshMs2(1, 2.5)
        assertEquals(2.5, AccelCalibrations.harshOrDefault(prefs, 1), 1e-9)
        assertEquals(StyleThresholds.HARSH_ACCEL_MS2, AccelCalibrations.harshOrDefault(prefs, 2), 1e-9)
        prefs.setHarshMs2(1, null)
        assertNull(prefs.harshMs2(1).first())
        assertEquals(StyleThresholds.HARSH_ACCEL_MS2, AccelCalibrations.harshOrDefault(null, 1), 1e-9)
    }
}

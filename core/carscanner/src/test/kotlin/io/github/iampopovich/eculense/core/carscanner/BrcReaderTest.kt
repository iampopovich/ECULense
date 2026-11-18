package io.github.iampopovich.eculense.core.carscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class BrcReaderTest {
    private val utc = ZoneOffset.UTC
    // 2026-05-08 03:46:53 как .NET ticks (100 нс от 0001-01-01)
    private val start = dotNetTicks(LocalDateTime.of(2026, 5, 8, 3, 46, 53))

    @Test
    fun `header is read as local wall time`() {
        val reader = BrcReader(BrcWriter().header(startTicks = start).bytes(), utc)
        assertEquals("Тестовая", reader.header.carName)
        assertEquals(LocalDateTime.of(2026, 5, 8, 3, 46, 53).toInstant(utc).toEpochMilli(), reader.header.startedAtMs)
        assertEquals("", reader.header.vin)
    }

    @Test
    fun `records come in file order`() {
        val bytes = BrcWriter().header(startTicks = start)
            .sensor(12, "Обороты двигателя", format = 6)
            .point(1.5, 12, 812.0)
            .gps(1.6, 10.0, 20.0)
            .bytes()
        val records = BrcReader(bytes, utc).records().toList()
        assertEquals(BrcRecord.SensorDef(12, "Обороты двигателя", "Обороты двигателя", 6), records[0])
        assertEquals(BrcRecord.DataPoint(1.5, 12, 812.0), records[1])
        assertTrue(records[2] is BrcRecord.GpsPoint)
    }

    @Test
    fun `long names use multi-byte length prefix`() {
        val longName = "Экономайзер топлива (на основе состояния топливной системы и положения дроссельной заслонки)"
        val record = BrcReader(BrcWriter().header(startTicks = start).sensor(229, longName, format = 0).bytes(), utc).records().single()
        assertEquals(longName, (record as BrcRecord.SensorDef).fullName)
    }

    @Test
    fun `dtc event carries absolute time and json`() {
        val at = dotNetTicks(LocalDateTime.of(2026, 5, 8, 3, 47, 10))
        val json = """{"Code":"P0113","RawCode":"0113","RequestCommand":"03","ResponseHeader":"C233F1"}"""
        val event = BrcReader(BrcWriter().header(startTicks = start).dtc(at, json).bytes(), utc).records().single() as BrcRecord.DtcEvent
        assertEquals(json, event.json)
        assertEquals(LocalDateTime.of(2026, 5, 8, 3, 47, 10).toInstant(utc).toEpochMilli(), event.atMs)
    }

    @Test
    fun `truncated last record is dropped, not an error`() {
        val full = BrcWriter().header(startTicks = start).point(1.0, 12, 800.0).point(2.0, 12, 810.0).bytes()
        val reader = BrcReader(full.copyOf(full.size - 5), utc)
        assertEquals(1, reader.records().count())
        assertTrue(reader.truncated)
    }

    @Test(expected = BrcFormatException::class)
    fun `unknown marker is an error`() {
        BrcReader(BrcWriter().header(startTicks = start).raw(byteArrayOf(1, 2, 3, 4, 0, 0, 0, 0)).bytes(), utc).records().toList()
    }

    @Test(expected = BrcFormatException::class)
    fun `not a car scanner file`() {
        BrcReader("hello world, definitely not BRC".encodeToByteArray(), utc)
    }

    @Test
    fun `sensor mapping`() {
        assertEquals("01.0C", CarScannerSensors.sensorId(12))
        assertEquals("01.21", CarScannerSensors.sensorId(41))
        assertEquals("elm.voltage", CarScannerSensors.sensorId(0))
        assertEquals("cs.20", CarScannerSensors.sensorId(20))
        assertEquals("°C", CarScannerSensors.unit(15))
        assertTrue(CarScannerSensors.isImported(12))
        assertFalse(CarScannerSensors.isImported(633)) // стоимость топлива — производная
    }

    private fun dotNetTicks(t: LocalDateTime): Long =
        (t.toInstant(utc).toEpochMilli() * 10_000) + DOTNET_EPOCH_TICKS
}

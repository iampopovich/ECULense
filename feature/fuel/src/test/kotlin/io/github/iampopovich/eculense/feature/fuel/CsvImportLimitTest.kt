package io.github.iampopovich.eculense.feature.fuel

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class CsvImportLimitTest {
    @Test
    fun `files up to the limit are read, larger ones are refused without reading them whole`() {
        val ok = "date;odometer;litres\n2026-01-01;1000;40\n"
        assertEquals(CsvRead.Text(ok), CsvImportLimit.read(ByteArrayInputStream(ok.toByteArray()), maxBytes = 64))
        assertEquals(CsvRead.Text("x".repeat(64)), CsvImportLimit.read(ByteArrayInputStream(ByteArray(64) { 'x'.code.toByte() }), maxBytes = 64))
        assertEquals(CsvRead.TooLarge, CsvImportLimit.read(ByteArrayInputStream(ByteArray(65)), maxBytes = 64))
        assertEquals(5 * 1024 * 1024, CsvImportLimit.MAX_BYTES)
    }
}

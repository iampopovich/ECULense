package io.github.iampopovich.eculense.core.carscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class CbzSettingsReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `short strings are ascii, not big-endian ints`() {
        val s = CbzSettingsReader.parse(
            mapOf(
                // как в бэкапе: 3 байта 0x4B6961 — это «Kia», а не число 4942177
                "SelectedBrand" to byteArrayOf(0x4B, 0x69, 0x61),
                "Currency" to "EUR".toByteArray(),
                "FuelPriceForLitre" to "1.79".toByteArray(),
                "SelectedProfileV2Name" to "OBD-II / EOBD".toByteArray(),
                "WiFiPort" to "35000".toByteArray(),
                "Use_km" to byteArrayOf(0),
                "Use_celcium" to byteArrayOf(1),
                "IOTimeout" to byteArrayOf(0, 0, 0, 10),
                "FuelConsumptionUnit" to byteArrayOf(0, 0, 0, 2),
            ),
        )
        assertEquals("Kia", s.brand)
        assertEquals("EUR", s.currency)
        assertEquals(1.79, s.fuelPricePerL!!, 1e-9)
        assertEquals("OBD-II / EOBD", s.diagProfile)
        assertEquals(35000, s.wifiPort)
        assertEquals(false, s.useKm)
        assertEquals(true, s.useCelsius)
        assertEquals(10, s.ioTimeout)
        assertEquals(2, s.fuelConsumptionUnit)
        assertNull(s.useL100)
        assertTrue(s.hasUnits)
    }

    @Test
    fun `comma decimal price and missing keys`() {
        val s = CbzSettingsReader.parse(mapOf("FuelPriceForLitre" to "57,3".toByteArray(), "DTCReadingSequence" to ByteArray(0)))
        assertEquals(57.3, s.fuelPricePerL!!, 1e-9)
        assertNull(s.brand)
        assertFalse(s.hasUnits)
    }

    @Test
    fun `backup meta reads settings, car and trips, ticks are 100 ns`() {
        val start = 1_767_000_000_000L
        val cbz = CbzWriter()
            .string("SelectedBrand", "Lada").string("Currency", "RUB").string("FuelPriceForLitre", "60.1")
            .bool("Use_km", true).bool("UseL100ForFuel", true)
            .car(CarScannerCar(id = 638_000_000_000_000_001, name = "Тестовая", year = 2010))
            .trip(CarScannerTrip(638_000_000_000_000_001, start, start + 600_000, 5_250_000, 480_000, 60.1, 5_000_000_000, 1_000_000_000, 6_000_000_000))
            .trip(CarScannerTrip(638_000_000_000_000_001, start - 86_400_000, start - 86_000_000, 0, 20_000, null, 0, 4_000_000_000, 4_000_000_000))
            .recording("2026-01-01 10-00-00.brc", byteArrayOf(1, 2, 3))
            .bytes(tmp.root)

        val meta = CbzSettingsReader.readMeta(ByteArrayInputStream(cbz), tmp.root, JdbcSqlite)

        assertEquals("Lada", meta.settings.brand)
        assertEquals(60.1, meta.settings.fuelPricePerL!!, 1e-9)
        assertEquals(listOf(CarScannerCar(638_000_000_000_000_001, "Тестовая", 2010)), meta.cars)
        assertEquals(2, meta.trips.size)
        val (older, newer) = meta.trips
        assertEquals(start - 86_400_000, older.startedUtcMs)
        assertNull(older.fuelPricePerL)
        assertEquals(5.25, newer.distanceKm, 1e-9)
        assertEquals(0.48, newer.fuelL, 1e-9)
        assertEquals(500_000L, newer.drivingMs)
        assertEquals(100_000L, newer.standingMs)
        assertEquals(newer.finishedUtcMs - newer.startedUtcMs, newer.totalMs)
        // временный файл БД удалён
        assertEquals(emptyList<String>(), tmp.root.list()!!.filter { it.startsWith("cs-drivecycles") })
    }

    @Test
    fun `backup without trips database still gives settings`() {
        val cbz = CbzWriter().string("SelectedBrand", "Kia").withoutDatabase().bytes(tmp.root)
        val meta = CbzSettingsReader.readMeta(ByteArrayInputStream(cbz), tmp.root, JdbcSqlite)
        assertEquals("Kia", meta.settings.brand)
        assertTrue(meta.cars.isEmpty())
        assertTrue(meta.trips.isEmpty())
    }
}

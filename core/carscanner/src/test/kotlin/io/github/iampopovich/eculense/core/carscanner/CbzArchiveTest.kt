package io.github.iampopovich.eculense.core.carscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.CRC32

class CbzArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `recordings come with size and crc from the central directory, meta reads trip ids`() {
        val rec = BrcWriter().header(startTicks = DOTNET_EPOCH_TICKS).sensor(12, "Обороты", format = 6).point(1.0, 12, 800.0).bytes()
        val file = tmp.newFile("b.cbz")
        file.writeBytes(
            CbzWriter().string("Currency", "RUB")
                .car(CarScannerCar(7, "Тестовая", 2010))
                .trip(CarScannerTrip(7, 1_000, 2_000, 5, 6, null, 1, 2, 3, id = 41))
                .recording("2026-01-01 10-00-00.brc", rec)
                .bytes(tmp.root),
        )

        CbzArchive(file).use { cbz ->
            val e = cbz.recordings.single()
            assertEquals("2026-01-01 10-00-00.brc", e.fileName)
            assertEquals(rec.size.toLong(), e.size)
            assertEquals(CRC32().apply { update(rec) }.value, e.crc)
            assertArrayEquals(rec, cbz.read(e))
            val meta = cbz.meta(tmp.root, JdbcSqlite)
            assertEquals("RUB", meta.settings.currency)
            assertEquals(41L, meta.trips.single().id)
            assertEquals("Тестовая", meta.cars.single().name)
        }
    }
}

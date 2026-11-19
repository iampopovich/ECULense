package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.carscanner.BrcWriter
import io.github.iampopovich.eculense.core.carscanner.CarScannerCar
import io.github.iampopovich.eculense.core.carscanner.CarScannerTrip
import io.github.iampopovich.eculense.core.carscanner.CbzWriter
import io.github.iampopovich.eculense.core.carscanner.DOTNET_EPOCH_TICKS
import io.github.iampopovich.eculense.core.carscanner.JdbcSqlite
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 1.27: the user keeps driving with Car Scanner and periodically transfers a new backup. The second backup = the first +
 * new recordings/trips/DTCs + an extended trip; manual profile edits in ECULense are not overwritten.
 * Synthetic data only: car «Тестовая», neutral prices and dates.
 */
class CarScannerIncrementalImportTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sessions = ImportSessions()
    private val dtcs = ImportDtcs()
    private val cars = FakeCars()
    private val trips = FakeTrips()
    private val units = FakeUnits()
    private val ledger = InMemoryImportLedger()
    private val importer by lazy { CarScannerImporter({ null }, sessions, dtcs, cars, trips, units, JdbcSqlite, tmp.root, ledger) }

    private val csCar = 638_000_000_000_000_042L
    private val start = 1_767_000_000_000L
    private val day = 86_400_000L
    private fun ticks(ms: Long) = DOTNET_EPOCH_TICKS + ms * 10_000
    private fun dtc(code: String) = """{"Code":"$code","RawCode":"${code.drop(1)}","RequestCommand":"03"}"""

    private fun recording(atMs: Long, code: String? = null) = BrcWriter().header(car = "Тестовая", startTicks = ticks(atMs))
        .sensor(12, "Обороты", format = 6).point(1.0, 12, 800.0).point(2.0, 12, 900.0)
        .apply { if (code != null) dtc(ticks(atMs + 1_000), dtc(code)) }
        .bytes()

    private fun trip(id: Long, at: Long, minutes: Long, distanceMm: Long) =
        CarScannerTrip(csCar, at, at + minutes * 60_000, distanceMm, distanceMm / 10, 60.5, minutes * 50_000_0000, minutes * 10_000_0000, minutes * 60_000_0000, id = id)

    private fun first() = CbzWriter()
        .string("SelectedBrand", "Lada").string("Currency", "RUB").string("FuelPriceForLitre", "60.5")
        .bool("Use_km", true).bool("Use_celcium", true)
        .car(CarScannerCar(csCar, "Тестовая", 2010))
        .trip(trip(1, start, 20, 12_000_000))
        .trip(trip(2, start + day, 10, 5_000_000))
        .recording("2026-01-01 10-00-00.brc", recording(start, "P0171"))
        .recording("2026-01-02 10-00-00.brc", recording(start + day))

    /** + a recording with a new code, + a trip, trip 2 extended, new fuel price and year, different units. */
    private fun second() = CbzWriter()
        .string("SelectedBrand", "Lada").string("Currency", "RUB").string("FuelPriceForLitre", "62.0")
        .bool("Use_km", false).bool("Use_celcium", true)
        .car(CarScannerCar(csCar, "Тестовая", 2011))
        .trip(trip(1, start, 20, 12_000_000))
        .trip(trip(2, start + day, 25, 15_000_000))
        .trip(trip(3, start + 2 * day, 15, 8_000_000))
        .recording("2026-01-01 10-00-00.brc", recording(start, "P0171"))
        .recording("2026-01-02 10-00-00.brc", recording(start + day))
        .recording("2026-01-03 10-00-00.brc", recording(start + 2 * day, "P0300"))

    private fun file(name: String, writer: CbzWriter): File = File(tmp.root, name).apply { writeBytes(writer.bytes(tmp.root)) }

    @Test
    fun `second backup adds only what is new, updates the extended trip and keeps manual edits`() = runTest {
        val r1 = importer.importFile(file("1.cbz", first()), "1.cbz")
        assertEquals(2, r1.imported)
        assertEquals(2, r1.tripsImported)
        assertEquals(1, r1.dtcEventsAdded)

        // the user edits in ECULense: name, model and units (°F)
        cars.save(cars.all.value.single().copy(name = "Моя", model = "Granta"))
        units.save(units.units.value.copy(celsius = false))
        val progress = mutableListOf<ImportProgress>()

        val r2 = importer.importFile(file("2.cbz", second()), "2.cbz") { progress += it }

        assertEquals(1, r2.imported)
        assertEquals(2, r2.skippedDuplicates)
        assertEquals("уже импортированные .brc не распаковываются", 2, r2.recordingsUnchanged)
        assertEquals(1, r2.tripsImported)
        assertEquals(1, r2.tripsUpdated)
        assertEquals(1, r2.tripsSkipped)
        assertEquals(1, r2.dtcEventsAdded)
        assertEquals(emptyList<String>(), r2.failed)

        assertEquals(3, sessions.started.size)
        assertEquals(3, trips.all.value.size)
        val extended = trips.all.value.single { it.startedAtMs == start + day }
        assertEquals(15.0, extended.distanceKm, 1e-9)
        assertEquals(start + day + 25 * 60_000, extended.endedAtMs)
        assertEquals(listOf("P0171", "P0300"), dtcs.snapshots.flatMap { it.second }.map { it.code })

        val car = cars.all.value.single()
        assertEquals("ручная правка имени сохранена", "Моя", car.name)
        assertEquals("Granta", car.model)
        assertEquals("цену пользователь не трогал — обновилась", 62.0, car.fuelPrice!!, 1e-9)
        assertEquals(2011, car.year)
        assertEquals(listOf("год", "цена топлива"), r2.carFieldsUpdated)
        assertFalse("единицы пользователь менял — бэкап их не перетирает", r2.unitsImported)
        assertFalse(units.units.value.celsius)
        assertTrue(units.units.value.metricDistance)

        assertEquals(3, progress.last().total)
        assertEquals(3, progress.last().done)
    }

    @Test
    fun `re-import of the same backup adds nothing`() = runTest {
        val f = file("2.cbz", second())
        importer.importFile(f, "2.cbz")
        val again = importer.importFile(f, "2.cbz")
        assertEquals(0, again.imported)
        assertEquals(3, again.skippedDuplicates)
        assertEquals(3, again.recordingsUnchanged)
        assertEquals(0, again.tripsImported)
        assertEquals(0, again.tripsUpdated)
        assertEquals(3, again.tripsSkipped)
        assertEquals(0, again.dtcEventsAdded)
        assertEquals(emptyList<String>(), again.carFieldsUpdated)
        assertFalse(again.unitsImported)
        assertEquals(3, sessions.started.size)
        assertEquals(3, trips.all.value.size)
    }

    @Test
    fun `untouched units follow the backup`() = runTest {
        importer.importFile(file("1.cbz", first()), "1.cbz")
        val r2 = importer.importFile(file("2.cbz", second()), "2.cbz")
        assertTrue(r2.unitsImported)
        assertFalse(units.units.value.metricDistance)
    }

    @Test
    fun `recording deleted in ECULense is imported again, a changed brc is re-read`() = runTest {
        importer.importFile(file("1.cbz", first()), "1.cbz")
        sessions.delete(sessions.started.first { it.externalId == "carscanner:2026-01-01 10-00-00.brc" }.id)
        val r = importer.importFile(file("1b.cbz", first()), "1b.cbz")
        assertEquals(1, r.imported)
        assertEquals(1, r.recordingsUnchanged)
    }
}

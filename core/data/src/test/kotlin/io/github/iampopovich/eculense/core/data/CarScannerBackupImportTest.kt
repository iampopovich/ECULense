package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.carscanner.BrcWriter
import io.github.iampopovich.eculense.core.carscanner.CarScannerCar
import io.github.iampopovich.eculense.core.carscanner.CarScannerTrip
import io.github.iampopovich.eculense.core.carscanner.CbzWriter
import io.github.iampopovich.eculense.core.carscanner.DOTNET_EPOCH_TICKS
import io.github.iampopovich.eculense.core.carscanner.JdbcSqlite
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.TripSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class CarScannerBackupImportTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sessions = ImportSessions()
    private val dtcs = ImportDtcs()
    private val cars = FakeCars()
    private val trips = FakeTrips()
    private val units = FakeUnits()
    private val importer by lazy { CarScannerImporter({ null }, sessions, dtcs, cars, trips, units, JdbcSqlite, tmp.root) }

    private val csCarId = 638_000_000_000_000_042L
    private val start = 1_767_000_000_000L
    private val p0171 = """{"Code":"P0171","RawCode":"0171","RequestCommand":"03"}"""

    private fun backup(): ByteArray {
        val rec = BrcWriter().header(car = "Тестовая", startTicks = DOTNET_EPOCH_TICKS + start * 10_000)
            .sensor(12, "Обороты", format = 6).point(1.0, 12, 800.0).dtc(DOTNET_EPOCH_TICKS + start * 10_000, p0171).bytes()
        return CbzWriter()
            .string("SelectedBrand", "Lada").string("Currency", "RUB").string("FuelPriceForLitre", "60.5")
            .string("SelectedProfileV2Name", "OBD-II / EOBD")
            .bool("Use_km", true).bool("Use_celcium", false).bool("UseL100ForFuel", true)
            .car(CarScannerCar(csCarId, "Тестовая", 2010))
            .trip(CarScannerTrip(csCarId, start, start + 1_200_000, 12_500_000, 1_100_000, 60.5, 10_000_000_000, 2_000_000_000, 12_000_000_000))
            .trip(CarScannerTrip(csCarId, start + 86_400_000, start + 87_000_000, 3_000_000, 400_000, 60.5, 5_000_000_000, 1_000_000_000, 6_000_000_000))
            .recording("2026-01-01 10-00-00.brc", rec)
            .bytes(tmp.root)
    }

    @Test
    fun `backup fills the untouched default car, units, price and trips`() = runTest {
        val bytes = backup()
        val report = importer.importStream({ ByteArrayInputStream(bytes) }, "backup.cbz")

        val car = cars.all.value.single()
        assertEquals(1L, car.id)
        assertEquals("Тестовая", car.name)
        assertEquals("Lada", car.brand)
        assertEquals(2010, car.year)
        assertEquals(60.5, car.fuelPrice!!, 1e-9)
        assertEquals("RUB", car.currency)
        assertEquals("OBD-II / EOBD", car.diagProfile)
        assertEquals("carscanner:$csCarId", car.externalId)
        assertEquals(1L, cars.active)

        assertTrue(report.unitsImported)
        assertFalse(units.units.value.celsius)
        assertTrue(units.units.value.metricDistance)

        assertEquals(2, report.tripsImported)
        assertEquals(0, report.tripsSkipped)
        val t = trips.all.value.first()
        assertEquals(1L, t.carId)
        assertEquals(12.5, t.distanceKm, 1e-9)
        assertEquals(1.1, t.fuelL, 1e-9)
        assertEquals(1_000_000L, t.drivingMs)
        assertEquals(200_000L, t.standingMs)
        assertEquals(TripSource.IMPORT_CARSCANNER, t.source)

        assertEquals(1, report.imported)
        assertEquals(1L, sessions.started.single().carId)
        assertEquals(listOf<Long?>(1L), dtcs.cars)
        assertEquals(ImportedCar("Тестовая", "Lada", 2010, 60.5, "RUB", created = false), report.car)
    }

    @Test
    fun `second import duplicates nothing and keeps user edits`() = runTest {
        val bytes = backup()
        importer.importStream({ ByteArrayInputStream(bytes) }, "backup.cbz")
        cars.save(cars.all.value.single().copy(name = "Моя ласточка", fuelPrice = 70.0, model = "Granta"))

        val again = importer.importStream({ ByteArrayInputStream(bytes) }, "backup.cbz")

        assertEquals(0, again.imported)
        assertEquals(1, again.skippedDuplicates)
        assertEquals(0, again.tripsImported)
        assertEquals(2, again.tripsSkipped)
        assertEquals(2, trips.all.value.size)
        val car = cars.all.value.single()
        assertEquals("Моя ласточка", car.name)
        assertEquals(70.0, car.fuelPrice!!, 1e-9)
        assertEquals("Granta", car.model)
        assertFalse(again.car!!.created)
    }

    @Test
    fun `user's own car is kept and the backup gets a new car`() = runTest {
        cars.save(cars.all.value.single().copy(name = "Своя", year = 2020))
        val bytes = backup()
        val report = importer.importStream({ ByteArrayInputStream(bytes) }, "backup.cbz")

        assertEquals(listOf("Своя", "Тестовая"), cars.all.value.map { it.name })
        val imported = cars.all.value.last()
        assertTrue(report.car!!.created)
        assertEquals(imported.id, sessions.started.single().carId)
        assertTrue(trips.all.value.all { it.carId == imported.id })
        assertNull(cars.active)
    }

    @Test
    fun `recordings imported before 1_25 move to the backup car on re-import`() = runTest {
        cars.save(cars.all.value.single().copy(name = "Своя"))
        sessions.start("old", io.github.iampopovich.eculense.core.model.SessionSource.IMPORT_CARSCANNER, null, start, "carscanner:2026-01-01 10-00-00.brc", carId = 1)
        val bytes = backup()
        importer.importStream({ ByteArrayInputStream(bytes) }, "backup.cbz")
        val backupCar = cars.byExternalId("carscanner:$csCarId")!!
        assertEquals(backupCar.id, sessions.started.single().carId)
        assertFalse(CarProfile(name = "Своя").isUntouchedDefault)
    }
}

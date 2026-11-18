package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.carscanner.BrcWriter
import io.github.iampopovich.eculense.core.carscanner.CarScannerCar
import io.github.iampopovich.eculense.core.carscanner.CarScannerTrip
import io.github.iampopovich.eculense.core.carscanner.CbzWriter
import io.github.iampopovich.eculense.core.carscanner.DOTNET_EPOCH_TICKS
import io.github.iampopovich.eculense.core.carscanner.JdbcSqlite
import io.github.iampopovich.eculense.core.model.SensorReading
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Только по переменным окружения, в обычной сборке пропускаются:
 * - ECU_SYNTHETIC_CBZ_DIR=/папка — пишет два синтетических бэкапа для проверки на устройстве (машина «Тестовая»,
 *   нейтральные даты/цены): ecu-test-1.cbz и ecu-test-2.cbz (= первый + 3 записи, 2 поездки, новый код, дописанная поездка).
 * - CARSCANNER_BACKUP=/путь.cbz — инкрементальный импорт настоящего бэкапа дважды, печатает только время и счётчики.
 */
class ImportBenchmarksTest {
    private val start = 1_767_000_000_000L
    private val day = 86_400_000L
    private val csCar = 638_000_000_000_000_042L

    private fun ticks(ms: Long) = DOTNET_EPOCH_TICKS + ms * 10_000

    private fun recording(atMs: Long, points: Int, code: String?): ByteArray {
        val w = BrcWriter().header(car = "Тестовая", startTicks = ticks(atMs))
            .sensor(12, "Обороты", format = 6).sensor(13, "Скорость", format = 3).sensor(5, "Температура ОЖ", format = 15)
        for (i in 0 until points) {
            val t = i * 0.5
            w.point(t, 12, 800.0 + (i % 400) * 5).point(t + 0.1, 13, (i % 200) * 0.5).point(t + 0.2, 5, 60.0 + (i % 300) * 0.1)
        }
        if (code != null) w.dtc(ticks(atMs + 30_000), """{"Code":"$code","RawCode":"${code.drop(1)}","RequestCommand":"03"}""")
        return w.bytes()
    }

    private fun trip(id: Long, at: Long, minutes: Long, km: Long) =
        CarScannerTrip(csCar, at, at + minutes * 60_000, km * 1_000_000, km * 80_000, 60.0, minutes * 500_000_000, minutes * 100_000_000, minutes * 600_000_000, id = id)

    private fun writer(recordings: Int, trips: Int, extendedTrip: Boolean, price: String): CbzWriter {
        val w = CbzWriter().string("SelectedBrand", "Lada").string("Currency", "RUB").string("FuelPriceForLitre", price)
            .bool("Use_km", true).bool("Use_celcium", true).car(CarScannerCar(csCar, "Тестовая", 2010))
        for (i in 0 until trips) w.trip(trip(i + 1L, start + i * day, if (extendedTrip && i == 1) 40 else 20, if (extendedTrip && i == 1) 25 else 10))
        for (i in 0 until recordings) {
            val code = when (i) { 2 -> "P0171"; recordings - 1 -> if (recordings > 20) "P0300" else null; else -> null }
            w.recording("2026-01-%02d 10-00-00.brc".format(i + 1), recording(start + i * day, 6_000, code))
        }
        return w
    }

    @Test
    fun `write two synthetic backups for the device pass`() {
        val dir = System.getenv("ECU_SYNTHETIC_CBZ_DIR")
        assumeTrue("задайте ECU_SYNTHETIC_CBZ_DIR", dir != null)
        val out = File(dir!!).apply { mkdirs() }
        val tmp = createTempDirectory("cbz-gen").toFile()
        try {
            File(out, "ecu-test-1.cbz").writeBytes(writer(recordings = 20, trips = 10, extendedTrip = false, price = "60.0").bytes(tmp))
            File(out, "ecu-test-2.cbz").writeBytes(writer(recordings = 23, trips = 12, extendedTrip = true, price = "62.0").bytes(tmp))
            println("synthetic backups: ${out.listFiles()!!.filter { it.name.startsWith("ecu-test-") }.map { "${it.name}=${it.length()}" }}")
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Хранит только счётчики показаний — настоящий бэкап (1,3 млн значений) не держим в памяти. */
    private class CountingSessions : SessionRepository {
        val external = mutableSetOf<String>()
        var readings = 0L
        private var next = 1L
        private val ids = mutableMapOf<Long, String>()
        override fun observeSessions() = flowOf(emptyList<io.github.iampopovich.eculense.core.model.Session>())
        override suspend fun start(name: String, source: io.github.iampopovich.eculense.core.model.SessionSource, vin: String?, startedAtMs: Long?, externalId: String?, carId: Long?): Long {
            val id = next++
            externalId?.let { external += it; ids[id] = it }
            return id
        }
        override suspend fun append(sessionId: Long, readings: List<SensorReading>) { this.readings += readings.size }
        override suspend fun finish(sessionId: Long, endedAtMs: Long?) = Unit
        override suspend fun readings(sessionId: Long) = emptyList<SensorReading>()
        override suspend fun delete(sessionId: Long) { ids.remove(sessionId)?.let { external -= it } }
        override suspend fun hasExternal(externalId: String) = externalId in external
        override suspend fun assignCar(externalId: String, carId: Long) = Unit
    }

    @Test
    fun `real backup imports incrementally`() = runTest {
        val path = System.getenv("CARSCANNER_BACKUP")
        assumeTrue("задайте CARSCANNER_BACKUP=/путь/к/бэкапу.cbz", path != null)
        val tmp = createTempDirectory("cbz-real").toFile()
        try {
            val sessions = CountingSessions()
            val importer = CarScannerImporter({ null }, sessions, ImportDtcs(), FakeCars(), FakeTrips(), FakeUnits(), JdbcSqlite, tmp, InMemoryImportLedger())
            var t = System.currentTimeMillis()
            val first = importer.importFile(File(path!!), "backup.cbz")
            val firstMs = System.currentTimeMillis() - t
            t = System.currentTimeMillis()
            val second = importer.importFile(File(path), "backup.cbz")
            val secondMs = System.currentTimeMillis() - t
            fun ImportReport.counts() = "recordings +$imported skipped=$skippedDuplicates unchanged=$recordingsUnchanged failed=${failed.size} " +
                "trips +$tripsImported updated=$tripsUpdated same=$tripsSkipped dtc +$dtcEventsAdded carFields=${carFieldsUpdated.size} units=$unitsImported"
            println("first: ${firstMs} ms, readings=${sessions.readings}, ${first.counts()}")
            println("second: ${secondMs} ms, ${second.counts()}")
            assertEquals(0, second.imported)
            assertEquals(0, second.tripsImported)
            assertEquals(0, second.tripsUpdated)
            assertEquals(0, second.dtcEventsAdded)
        } finally {
            tmp.deleteRecursively()
        }
    }
}

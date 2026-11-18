package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.carscanner.BrcWriter
import io.github.iampopovich.eculense.core.carscanner.DOTNET_EPOCH_TICKS
import io.github.iampopovich.eculense.core.carscanner.JdbcSqlite
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.Session
import io.github.iampopovich.eculense.core.model.SessionSource
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class ImportSessions : SessionRepository {
    data class Started(val id: Long, val name: String, val source: SessionSource, val startedAtMs: Long?, val externalId: String?, val carId: Long? = null)

    val started = mutableListOf<Started>()
    val readings = mutableMapOf<Long, MutableList<SensorReading>>()
    val ended = mutableMapOf<Long, Long?>()
    val deleted = mutableListOf<Long>()
    private var nextId = 1L

    override fun observeSessions() = flowOf(emptyList<Session>())
    override suspend fun start(name: String, source: SessionSource, vin: String?, startedAtMs: Long?, externalId: String?, carId: Long?): Long {
        val id = nextId++
        started += Started(id, name, source, startedAtMs, externalId, carId)
        return id
    }
    override suspend fun assignCar(externalId: String, carId: Long) {
        started.replaceAll { if (it.externalId == externalId) it.copy(carId = carId) else it }
    }
    override suspend fun append(sessionId: Long, readings: List<SensorReading>) {
        this.readings.getOrPut(sessionId) { mutableListOf() } += readings
    }
    override suspend fun finish(sessionId: Long, endedAtMs: Long?) {
        ended[sessionId] = endedAtMs
    }
    override suspend fun readings(sessionId: Long) = readings[sessionId].orEmpty()
    override suspend fun delete(sessionId: Long) {
        deleted += sessionId
        started.removeAll { it.id == sessionId }
        readings.remove(sessionId)
    }
    override suspend fun hasExternal(externalId: String) = started.any { it.externalId == externalId }
}

internal class ImportDtcs : DtcRepository {
    val snapshots = mutableListOf<Pair<Long?, List<Dtc>>>()
    val cars = mutableListOf<Long?>()
    override suspend fun saveSnapshot(dtcs: List<Dtc>, vin: String?, takenAtMs: Long?, carId: Long?) {
        snapshots += takenAtMs to dtcs
        cars += carId
    }
    override suspend fun hasEvent(code: String, fromMs: Long, toMs: Long): Boolean =
        snapshots.any { (at, list) -> at != null && at in fromMs..toMs && list.any { it.code == code } }
}

class CarScannerImporterTest {
    private val sessions = ImportSessions()
    private val dtcs = ImportDtcs()
    private val importer = CarScannerImporter(
        { null }, sessions, dtcs, FakeCars(), FakeTrips(), FakeUnits(), JdbcSqlite, File(System.getProperty("java.io.tmpdir")),
    )
    private val startTicks = DOTNET_EPOCH_TICKS + 1_778_212_013_000L * 10_000
    private val p0113 = """{"Code":"P0113","RawCode":"0113","RequestCommand":"03","ResponseHeader":""}"""
    private val p0113WithHeader = """{"Code":"P0113","RawCode":"0113","RequestCommand":"03","ResponseHeader":"C233F1"}"""
    private val p1847Pending = """{"Code":"P1847","RawCode":"1847","RequestCommand":"07"}"""

    private fun recording() = BrcWriter().header(startTicks = startTicks)
        .sensor(12, "Обороты двигателя", format = 6)
        .sensor(5, "Температура ОЖ", format = 15)
        .sensor(633, "Стоимость топлива", format = 0)
        .point(1.5, 12, 812.0)
        .point(3.2, 12, 905.0)
        .point(3.3, 5, Double.POSITIVE_INFINITY)
        .point(3.4, 5, Double.NaN)
        .point(3.5, 5, 88.0)
        .point(4.0, 633, 12.5)
        .gps(4.1, 10.0, 20.0)
        .dtc(startTicks + 50_000_000, p0113)
        .dtc(startTicks + 50_000_000, p0113WithHeader)
        .dtc(startTicks + 60_000_000, p1847Pending)
        .bytes()

    @Test
    fun `raw sensors become readings with units and absolute time`() = runTest {
        assertTrue(importer.importRecording("2026-01-02 12-00-00.brc", recording()))

        val session = sessions.started.single()
        assertEquals("carscanner:2026-01-02 12-00-00.brc", session.externalId)
        assertEquals(SessionSource.IMPORT_CARSCANNER, session.source)
        assertEquals("Car Scanner · 2026-01-02 12-00-00", session.name)
        val start = session.startedAtMs!!
        val rpm = sessions.readings(session.id).filter { it.sensorId == "01.0C" }
        assertEquals(listOf(SensorReading("01.0C", 812.0, "rpm", start + 1500), SensorReading("01.0C", 905.0, "rpm", start + 3200)), rpm)
        assertEquals(start + 3500, sessions.ended[session.id])
    }

    @Test
    fun `inf, NaN and derived sensors are skipped`() = runTest {
        importer.importRecording("a.brc", recording())
        val all = sessions.readings(1L)
        assertEquals(listOf(SensorReading("01.05", 88.0, "°C", sessions.started.single().startedAtMs!! + 3500)), all.filter { it.sensorId == "01.05" })
        assertFalse(all.any { it.sensorId == "cs.633" })
        assertEquals(3, all.size)
    }

    @Test
    fun `same dtc written twice becomes one snapshot, pending kind from request 07`() = runTest {
        importer.importRecording("a.brc", recording())
        assertEquals(listOf(listOf(Dtc("P0113", DtcKind.STORED)), listOf(Dtc("P1847", DtcKind.PENDING))), dtcs.snapshots.map { it.second })
        assertEquals(sessions.started.single().startedAtMs!! + 5_000, dtcs.snapshots.first().first)
    }

    @Test
    fun `second import of the same file is a duplicate`() = runTest {
        assertTrue(importer.importRecording("a.brc", recording()))
        assertFalse(importer.importRecording("a.brc", recording()))
        assertEquals(1, sessions.started.size)
        assertEquals(2, dtcs.snapshots.size)
    }

    @Test
    fun `separate reads of the same code in one recording stay separate events`() = runTest {
        // Car Scanner: чтение без заголовков, через 9 с — с заголовками, через 2 мин — снова
        val rec = BrcWriter().header(car = "Тестовая", startTicks = startTicks)
            .dtc(startTicks + 10_000_000, p0113)
            .dtc(startTicks + 100_000_000, p0113WithHeader)
            .dtc(startTicks + 1_300_000_000, p0113)
            .bytes()
        importer.importRecording("a.brc", rec)
        val start = sessions.started.single().startedAtMs!!
        assertEquals(listOf(start + 1_000, start + 10_000, start + 130_000), dtcs.snapshots.map { it.first })
    }

    @Test
    fun `header and no-header twin a moment apart is one event`() = runTest {
        val rec = BrcWriter().header(car = "Тестовая", startTicks = startTicks)
            .dtc(startTicks + 10_000_000, p0113)
            .dtc(startTicks + 12_000_000, p0113WithHeader)
            .bytes()
        importer.importRecording("a.brc", rec)
        assertEquals(1, dtcs.snapshots.size)
    }

    @Test
    fun `re-import backfills events lost by the old per-recording dedup without duplicating`() = runTest {
        val rec = BrcWriter().header(car = "Тестовая", startTicks = startTicks)
            .dtc(startTicks + 10_000_000, p0113)
            .dtc(startTicks + 1_300_000_000, p0113)
            .bytes()
        importer.importRecording("a.brc", rec)
        val start = sessions.started.single().startedAtMs!!
        // как на телефоне после старого импорта: из двух событий записи осталось первое
        dtcs.snapshots.removeAt(1)

        val report = importer.importStream({ ByteArrayInputStream(rec) }, "a.brc")
        assertEquals(1, report.skippedDuplicates)
        assertEquals(1, report.dtcEventsAdded)
        assertEquals(listOf(start + 1_000, start + 130_000), dtcs.snapshots.mapNotNull { it.first }.sorted())

        val again = importer.importStream({ ByteArrayInputStream(rec) }, "a.brc")
        assertEquals(0, again.dtcEventsAdded)
        assertEquals(2, dtcs.snapshots.size)
    }

    @Test
    fun `broken recording leaves no half-imported session`() = runTest {
        val broken = BrcWriter().header(startTicks = startTicks)
            .point(1.0, 12, 800.0)
            .raw(byteArrayOf(1, 2, 3, 4, 0, 0, 0, 0))
            .bytes()
        val report = importer.importStream({ ByteArrayInputStream(broken) }, "broken.brc")
        assertEquals(0, report.imported)
        assertEquals(1, report.failed.size)
        assertTrue(sessions.started.isEmpty())
        assertFalse(sessions.hasExternal("carscanner:broken.brc"))
    }

    @Test
    fun `cbz backup imports every recording and reports progress`() = runTest {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                fun put(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
                put("Use_km", byteArrayOf(1))
                put("storage/emulated/0/Android/data/com.ovz.carscanner/files/records/one.brc", recording())
                put("storage/emulated/0/Android/data/com.ovz.carscanner/files/records/two.brc", recording())
                put("storage/emulated/0/Android/data/com.ovz.carscanner/files/records/bad.brc", byteArrayOf(1, 2, 3))
            }
        }.toByteArray()
        val progress = mutableListOf<Int>()

        val first = importer.importStream({ ByteArrayInputStream(zip) }, "backup.cbz") { progress += it }
        val second = importer.importStream({ ByteArrayInputStream(zip) }, "backup.cbz")

        assertEquals(2, first.imported)
        assertEquals(0, first.skippedDuplicates)
        assertEquals(1, first.failed.size)
        assertEquals(listOf(1, 2, 3), progress)
        assertEquals(ImportReport(imported = 0, skippedDuplicates = 2, failed = first.failed, unitsImported = true), second)
    }
}

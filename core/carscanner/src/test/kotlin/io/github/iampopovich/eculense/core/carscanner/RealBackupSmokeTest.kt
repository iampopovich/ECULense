package io.github.iampopovich.eculense.core.carscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class RealBackupSmokeTest {
    @Test
    fun `every recording in a real backup parses`() {
        val path = System.getenv("CARSCANNER_BACKUP")
        assumeTrue("задайте CARSCANNER_BACKUP=/путь/к/бэкапу.cbz", path != null)
        var files = 0; var points = 0L; var dtc = 0; var truncated = 0
        val failures = mutableListOf<String>()
        File(path!!).inputStream().buffered().use { input ->
            CbzBackup.forEachRecording(input) { name, bytes ->
                files++
                runCatching {
                    val reader = BrcReader(bytes)
                    reader.records().forEach { r ->
                        if (r is BrcRecord.DataPoint) points++
                        if (r is BrcRecord.DtcEvent) dtc++
                    }
                    if (reader.truncated) truncated++
                }.onFailure { failures += "$name: ${it.message}" }
            }
        }
        println("files=$files points=$points dtcEvents=$dtc truncated=$truncated failures=$failures")
        assertEquals(emptyList<String>(), failures)
    }

    /** Печатает только агрегаты (без имён, координат, адресов) — сверка с Car Scanner глазами, значения не коммитим. */
    @Test
    fun `settings, car and trips of a real backup`() {
        val path = System.getenv("CARSCANNER_BACKUP")
        assumeTrue("задайте CARSCANNER_BACKUP=/путь/к/бэкапу.cbz", path != null)
        val tmp = createTempDirectory("cbz-smoke").toFile()
        try {
            val meta = File(path!!).inputStream().buffered().use { CbzSettingsReader.readMeta(it, tmp, JdbcSqlite) }
            val s = meta.settings
            val trips = meta.trips
            val zone = java.time.ZoneId.systemDefault()
            fun month(ms: Long) = java.time.YearMonth.from(java.time.Instant.ofEpochMilli(ms).atZone(zone))
            println(
                "brand=${s.brand} currency=${s.currency} price=${s.fuelPricePerL} km=${s.useKm} celsius=${s.useCelsius} l100=${s.useL100} " +
                    "cars=${meta.cars.size} carYear=${meta.cars.firstOrNull()?.year} carNameLength=${meta.cars.firstOrNull()?.name?.length}",
            )
            println(
                "trips=${trips.size} period=${trips.minOfOrNull { month(it.startedUtcMs) }}..${trips.maxOfOrNull { month(it.startedUtcMs) }} " +
                    "km=%.1f fuelL=%.1f drivingH=%.1f standingH=%.1f".format(
                        trips.sumOf { it.distanceKm }, trips.sumOf { it.fuelL },
                        trips.sumOf { it.drivingMs } / 3.6e6, trips.sumOf { it.standingMs } / 3.6e6,
                    ),
            )
            assertTrue(meta.cars.isNotEmpty())
            assertTrue(trips.isNotEmpty())
            // ticks = 100 нс: сумма движение+стоянка совпадает с длительностью поездки
            trips.forEach { t -> assertEquals((t.finishedUtcMs - t.startedUtcMs).toDouble(), t.totalMs.toDouble(), 1_000.0) }
        } finally {
            tmp.deleteRecursively()
        }
    }
}

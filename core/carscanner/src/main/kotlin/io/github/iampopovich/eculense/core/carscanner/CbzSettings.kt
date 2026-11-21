package io.github.iampopovich.eculense.core.carscanner

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Car Scanner settings from the .cbz root: one file = one key.
 * Strings are UTF-8 without a length (short ones like «RUB», «Kia», «57.3» are easily mistaken for numbers; they are NOT ints),
 * numbers are 4-byte big-endian, flags are 1 byte. The type comes from the list of known keys, not guessed from the length.
 */
data class CarScannerSettings(
    val brand: String? = null,
    val diagProfile: String? = null,
    val currency: String? = null,
    val fuelPricePerL: Double? = null,
    val useKm: Boolean? = null,
    val useCelsius: Boolean? = null,
    val useL100: Boolean? = null,
    val useLiters: Boolean? = null,
    val useUsGallon: Boolean? = null,
    val pressureKpa: Boolean? = null,
    val horsePower: Boolean? = null,
    val torqueNm: Boolean? = null,
    val flowGramsPerSecond: Boolean? = null,
    val accelerationG: Boolean? = null,
    val fuelConsumptionUnit: Int? = null,
    /** Host of the Wi-Fi adapter set up in Car Scanner (key WiFiServer). */
    val wifiServer: String? = null,
    val wifiPort: Int? = null,
    /** Car Scanner IOTimeout / SendDelay: no counterpart in our adapter settings (ELM327 ATST/adaptive timing), not imported. */
    val ioTimeout: Int? = null,
    val sendDelay: Int? = null,
) {
    /**
     * «host:port» of the Wi-Fi adapter for the Connect screen; null when the host is missing or not a plain IPv4/host
     * name, or the port is out of range. Without a stored port the ELM327 Wi-Fi default 35000 is used (the address our
     * Wi-Fi factory lists by default and the factory setting of common ELM327 Wi-Fi clones).
     */
    val wifiEndpoint: String?
        get() {
            val host = wifiServer?.trim()?.takeIf { HOST.matches(it) } ?: return null
            val port = (wifiPort ?: DEFAULT_WIFI_PORT).takeIf { it in 1..65_535 } ?: return null
            return "$host:$port"
        }

    private companion object {
        const val DEFAULT_WIFI_PORT = 35_000
        val HOST = Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
    }

    /** Whether the backup has at least one unit-of-measure flag. */
    val hasUnits: Boolean
        get() = listOf(useKm, useCelsius, useL100, useLiters, useUsGallon, pressureKpa, horsePower, torqueNm, flowGramsPerSecond, accelerationG)
            .any { it != null }
}

/** A car from the cars table (drivecyclesv2.db). settings_string is not read: it's a closed encoded blob. */
data class CarScannerCar(val id: Long, val name: String, val year: Int?)

/**
 * A row of the trips table. *_ticks are .NET ticks (100 ns): total_ticks / (finished − started) ≈ 10 000 on a real backup.
 */
data class CarScannerTrip(
    val carId: Long?,
    val startedUtcMs: Long,
    val finishedUtcMs: Long,
    val distanceMm: Long,
    val fuelUl: Long,
    val fuelPricePerL: Double?,
    val drivingTicks: Long,
    val standingTicks: Long,
    val totalTicks: Long,
    /** trips.id in drivecyclesv2.db (Car Scanner autoincrement); null for synthetic data without an id. For reporting/diagnostics only. */
    val id: Long? = null,
) {
    val distanceKm: Double get() = distanceMm / 1_000_000.0
    val fuelL: Double get() = fuelUl / 1_000_000.0
    val drivingMs: Long get() = drivingTicks / TICKS_PER_MS
    val standingMs: Long get() = standingTicks / TICKS_PER_MS
    val totalMs: Long get() = totalTicks / TICKS_PER_MS

    companion object {
        const val TICKS_PER_MS = 10_000L
    }
}

/** Everything except the .brc recordings: settings, cars, trips. */
data class CarScannerBackupMeta(
    val settings: CarScannerSettings,
    val cars: List<CarScannerCar>,
    val trips: List<CarScannerTrip>,
)

/** Minimal SQLite access: android.database.sqlite on Android, sqlite-jdbc in JVM tests. */
interface SqliteSource : Closeable {
    /** Column values in SELECT order: Long, Double, String, ByteArray or null. */
    fun rows(sql: String): List<List<Any?>>
}

fun interface SqliteOpener {
    fun open(file: File): SqliteSource
}

object CbzSettingsReader {
    /** entries: files from the archive root (name without "/") → contents. */
    fun parse(entries: Map<String, ByteArray>): CarScannerSettings {
        fun str(key: String) = entries[key]?.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)?.trim()
        fun bool(key: String) = entries[key]?.takeIf { it.size == 1 }?.let { it[0].toInt() != 0 }
        fun int(key: String) = entries[key]?.takeIf { it.size == 4 }?.let { bytes ->
            bytes.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        }
        return CarScannerSettings(
            brand = str("SelectedBrand"),
            diagProfile = str("SelectedProfileV2Name"),
            currency = str("Currency"),
            // «57.3» or «57,3» depending on the locale of the phone where the backup was made
            fuelPricePerL = str("FuelPriceForLitre")?.replace(',', '.')?.toDoubleOrNull(),
            useKm = bool("Use_km"),
            useCelsius = bool("Use_celcium"),
            useL100 = bool("UseL100ForFuel"),
            useLiters = bool("UseLitersForVolume"),
            useUsGallon = bool("UseUSGallon"),
            pressureKpa = bool("Pressure_use_kpa"),
            horsePower = bool("UseHoursePower"),
            torqueNm = bool("UseNmForTorque"),
            flowGramsPerSecond = bool("Flow_use_grams_sec"),
            accelerationG = bool("AccelerationUseG"),
            fuelConsumptionUnit = int("FuelConsumptionUnit"),
            wifiServer = str("WiFiServer"),
            wifiPort = str("WiFiPort")?.toIntOrNull(),
            ioTimeout = int("IOTimeout"),
            sendDelay = int("SendDelay"),
        )
    }

    fun readCars(db: SqliteSource): List<CarScannerCar> = runCatching {
        db.rows("SELECT id, name, year FROM cars ORDER BY id").map { r ->
            CarScannerCar(id = (r[0] as Number).toLong(), name = r[1] as? String ?: "", year = (r[2] as? Number)?.toInt()?.takeIf { it > 0 })
        }
    }.getOrDefault(emptyList())

    fun readTrips(db: SqliteSource): List<CarScannerTrip> = runCatching {
        db.rows(
            "SELECT car_id, started_utc_ms, finished_utc_ms, distance_total_mm, fuel_used_ul, fuel_price_per_l_real, " +
                "driving_ticks, standing_ticks, total_ticks, id FROM trips WHERE started_utc_ms IS NOT NULL ORDER BY started_utc_ms",
        ).map { r ->
            fun long(i: Int) = (r[i] as? Number)?.toLong() ?: 0L
            CarScannerTrip(
                carId = (r[0] as? Number)?.toLong(),
                startedUtcMs = long(1),
                finishedUtcMs = long(2),
                distanceMm = long(3),
                fuelUl = long(4),
                fuelPricePerL = (r[5] as? Number)?.toDouble()?.takeIf { it > 0.0 },
                drivingTicks = long(6),
                standingTicks = long(7),
                totalTicks = long(8),
                id = (r[9] as? Number)?.toLong(),
            )
        }
    }.getOrDefault(emptyList())

    /**
     * A single pass over the archive: settings from the root + drivecyclesv2.db.bak (to a temp file, since SQLite reads only from disk).
     * .brc entries are skipped without being read into memory.
     */
    fun readMeta(input: InputStream, tempDir: File, sqlite: SqliteOpener): CarScannerBackupMeta {
        val settings = mutableMapOf<String, ByteArray>()
        var dbFile: File? = null
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    when {
                        '/' !in entry.name && entry.name.length < 128 -> settings[entry.name] = zip.readBytes()
                        entry.name.endsWith(DB_SUFFIX) && dbFile == null -> {
                            val file = File.createTempFile("cs-drivecycles", ".db", tempDir)
                            dbFile = file
                            file.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
            val (cars, trips) = dbFile?.let { file -> sqlite.open(file).use { db -> readCars(db) to readTrips(db) } }
                ?: (emptyList<CarScannerCar>() to emptyList())
            return CarScannerBackupMeta(parse(settings), cars, trips)
        } finally {
            dbFile?.delete()
        }
    }

    private const val DB_SUFFIX = "drivecyclesv2.db.bak"
}

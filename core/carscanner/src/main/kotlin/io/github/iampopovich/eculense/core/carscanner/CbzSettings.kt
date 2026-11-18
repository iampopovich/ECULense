package io.github.iampopovich.eculense.core.carscanner

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Настройки Car Scanner из корня .cbz: один файл = один ключ.
 * Строки — UTF-8 без длины (короткие «RUB», «Kia», «57.3» легко принять за число — это НЕ int),
 * числа — 4 байта big-endian, флаги — 1 байт. Тип берём из списка известных ключей, а не угадываем по длине.
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
    val wifiPort: Int? = null,
    val ioTimeout: Int? = null,
    val sendDelay: Int? = null,
) {
    /** Есть ли в бэкапе хоть один флаг единиц измерения. */
    val hasUnits: Boolean
        get() = listOf(useKm, useCelsius, useL100, useLiters, useUsGallon, pressureKpa, horsePower, torqueNm, flowGramsPerSecond, accelerationG)
            .any { it != null }
}

/** Машина из таблицы cars (drivecyclesv2.db). settings_string не читаем: это закрытый закодированный блоб. */
data class CarScannerCar(val id: Long, val name: String, val year: Int?)

/**
 * Строка таблицы trips. *_ticks — .NET ticks (100 нс): total_ticks / (finished − started) ≈ 10 000 на реальном бэкапе.
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
    /** trips.id в drivecyclesv2.db (autoincrement Car Scanner); null — синтетика без id. Только для отчёта/диагностики. */
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

/** Всё, что кроме записей .brc: настройки, машины, поездки. */
data class CarScannerBackupMeta(
    val settings: CarScannerSettings,
    val cars: List<CarScannerCar>,
    val trips: List<CarScannerTrip>,
)

/** Минимальный доступ к SQLite: на Android — android.database.sqlite, в JVM-тестах — sqlite-jdbc. */
interface SqliteSource : Closeable {
    /** Значения колонок в порядке SELECT: Long, Double, String, ByteArray или null. */
    fun rows(sql: String): List<List<Any?>>
}

fun interface SqliteOpener {
    fun open(file: File): SqliteSource
}

object CbzSettingsReader {
    /** entries — файлы из корня архива (имя без «/») → содержимое. */
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
            // «57.3» или «57,3» в зависимости от локали телефона, где делали бэкап
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
     * Один проход по архиву: настройки из корня + drivecyclesv2.db.bak (во временный файл — SQLite читает только с диска).
     * Записи .brc пропускаются без чтения в память.
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

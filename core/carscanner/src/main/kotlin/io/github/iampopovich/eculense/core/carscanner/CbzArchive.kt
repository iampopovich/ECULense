package io.github.iampopovich.eculense.core.carscanner

import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** Запись .brc в бэкапе: имя файла без пути, размер и CRC-32 из центрального каталога zip (читаются без распаковки). */
data class CbzRecordingEntry(val fileName: String, val size: Long, val crc: Long, internal val entryName: String)

/**
 * Бэкап .cbz, скопированный на диск (из SAF — во временный кэш). В отличие от потокового [CbzBackup], zip читается
 * с произвольным доступом: список записей с размерами и CRC известен сразу (прогресс «23 из 73», пропуск уже
 * импортированных .brc без распаковки), а распаковывается только нужное.
 */
class CbzArchive(file: File) : Closeable {
    private val zip = ZipFile(file)

    val recordings: List<CbzRecordingEntry> = zip.entries().asSequence()
        .filter { !it.isDirectory && it.name.endsWith(".brc", ignoreCase = true) }
        .map { CbzRecordingEntry(it.name.substringAfterLast('/'), it.size, it.crc, it.name) }
        .toList()

    fun read(entry: CbzRecordingEntry): ByteArray =
        zip.getInputStream(zip.getEntry(entry.entryName) ?: error("нет записи в архиве")).use { it.readBytes() }

    /** Настройки из корня + машины и поездки из drivecyclesv2.db.bak (во временный файл — SQLite читает только с диска). */
    fun meta(tempDir: File, sqlite: SqliteOpener): CarScannerBackupMeta {
        val settings = mutableMapOf<String, ByteArray>()
        var dbFile: File? = null
        try {
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                when {
                    '/' !in entry.name && entry.name.length < 128 -> settings[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
                    entry.name.endsWith(DB_SUFFIX) && dbFile == null -> {
                        val f = File.createTempFile("cs-drivecycles", ".db", tempDir)
                        dbFile = f
                        zip.getInputStream(entry).use { input -> f.outputStream().use { input.copyTo(it) } }
                    }
                }
            }
            val (cars, trips) = dbFile?.let { f -> sqlite.open(f).use { db -> CbzSettingsReader.readCars(db) to CbzSettingsReader.readTrips(db) } }
                ?: (emptyList<CarScannerCar>() to emptyList())
            return CarScannerBackupMeta(CbzSettingsReader.parse(settings), cars, trips)
        } finally {
            dbFile?.delete()
        }
    }

    override fun close() = zip.close()

    private companion object {
        const val DB_SUFFIX = "drivecyclesv2.db.bak"
    }
}

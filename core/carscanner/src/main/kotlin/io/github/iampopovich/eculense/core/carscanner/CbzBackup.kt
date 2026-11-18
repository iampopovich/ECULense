package io.github.iampopovich.eculense.core.carscanner

import java.io.InputStream
import java.util.zip.ZipInputStream

/** Полный бэкап Car Scanner — zip: настройки (по файлу на ключ), records/<имя>.brc, cache/drivecyclesv2.db.bak. */
object CbzBackup {
    /** Потоково, без распаковки на диск: самый большой .brc в бэкапе пользователя ~23 МБ. */
    inline fun forEachRecording(input: InputStream, block: (fileName: String, bytes: ByteArray) -> Unit) {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".brc", ignoreCase = true)) {
                    block(entry.name.substringAfterLast('/'), zip.readBytes())
                }
            }
        }
    }
}

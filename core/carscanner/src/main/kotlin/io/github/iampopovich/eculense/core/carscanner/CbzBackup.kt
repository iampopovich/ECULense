package io.github.iampopovich.eculense.core.carscanner

import java.io.InputStream
import java.util.zip.ZipInputStream

/** Full Car Scanner backup — a zip: settings (one file per key), records/<name>.brc, cache/drivecyclesv2.db.bak. */
object CbzBackup {
    /** Streaming, without extracting to disk: the largest .brc in the user's backup is ~23 MB. */
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

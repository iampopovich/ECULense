package io.github.iampopovich.eculense.core.data

import java.io.File

/**
 * Share files in the cache (cache/exports, cache/reports) are written once for an ACTION_SEND and never read again by
 * the app: before a new one is written, the previous ones in the same folder go, so the cache does not grow with every
 * share (a session CSV of a 100k-reading recording is ~5 MB).
 */
object ExportFiles {
    /** Creates [dir] if needed, deletes the files already in it and returns `dir/name` for the new export. */
    fun fresh(dir: File, name: String): File {
        dir.mkdirs()
        dir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        return File(dir, name)
    }
}

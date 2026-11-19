package io.github.iampopovich.eculense.core.datapacks

import java.io.File

/**
 * Pack storage: `<root>/<id>/<version>/files` + `<root>/<id>/current` (which version is active).
 * A new version is assembled in `.staging-*`, then the directory is renamed and `current` is replaced via rename, so a reader
 * sees either the old or the new version in full. Old versions are deleted after the switch.
 */
class PackStore(val root: File) {
    data class Installed(val id: String, val version: String, val dir: File, val sizeBytes: Long) {
        fun file(name: String): File = File(dir, name)
    }

    private fun packDir(id: String) = File(root, id)
    private fun currentFile(id: String) = File(packDir(id), "current")
    private fun updateFile(id: String) = File(packDir(id), "update")

    fun installed(id: String): Installed? {
        val version = currentFile(id).takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null } ?: return null
        val dir = File(packDir(id), versionDir(version))
        if (!dir.isDirectory) return null
        return Installed(id, version, dir, dir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
    }

    /** File of the active version, or null if the pack isn't downloaded. */
    fun file(id: String, name: String): File? = installed(id)?.file(name)?.takeIf { it.isFile }

    /** Directory for assembling a version. keep = true means resume: the previous attempt's contents (`.part`) are kept. */
    fun staging(id: String, version: String, keep: Boolean = false): File =
        File(packDir(id), ".staging-${versionDir(version)}").apply {
            if (!keep) deleteRecursively()
            mkdirs()
        }

    /** How many bytes are already in staging (resume after a dropped connection); 0 means nothing. */
    fun partialBytes(id: String): Long =
        packDir(id).listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(".staging-") }
            .sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    @Synchronized
    fun commit(id: String, version: String, staged: File): Installed {
        val target = File(packDir(id), versionDir(version))
        if (target.exists()) {
            // reinstalling the same version: move the old directory aside first so the rename is atomic
            val old = File(packDir(id), ".old-${versionDir(version)}-${System.nanoTime()}")
            check(target.renameTo(old)) { "could not move the old version $target" }
        }
        check(staged.renameTo(target)) { "could not rename $staged → $target" }
        val tmp = File(packDir(id), "current.tmp")
        tmp.writeText(version)
        check(tmp.renameTo(currentFile(id))) { "could not switch the version of $id" }
        packDir(id).listFiles().orEmpty()
            .filter { it.isDirectory && it != target }
            .forEach { it.deleteRecursively() }
        if (availableUpdate(id) == version) setAvailableUpdate(id, null)
        return installed(id)!!
    }

    @Synchronized
    fun delete(id: String) {
        packDir(id).deleteRecursively()
    }

    fun availableUpdate(id: String): String? = updateFile(id).takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }

    fun setAvailableUpdate(id: String, version: String?) {
        if (version == null) {
            updateFile(id).delete()
        } else {
            packDir(id).mkdirs()
            updateFile(id).writeText(version)
        }
    }
}

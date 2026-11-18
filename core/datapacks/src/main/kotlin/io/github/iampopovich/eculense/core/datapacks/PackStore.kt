package io.github.iampopovich.eculense.core.datapacks

import java.io.File

/**
 * Хранилище пакетов: `<root>/<id>/<версия>/файлы` + `<root>/<id>/current` (какая версия активна).
 * Новая версия собирается в `.staging-*`, затем каталог переименовывается и `current` заменяется rename-ом — читатель
 * видит либо старую, либо новую версию целиком. Старые версии удаляются после переключения.
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

    /** Файл активной версии или null, если пакет не скачан. */
    fun file(id: String, name: String): File? = installed(id)?.file(name)?.takeIf { it.isFile }

    /** Каталог для сборки версии. keep = true — докачка: содержимое прошлой попытки (`.part`) остаётся. */
    fun staging(id: String, version: String, keep: Boolean = false): File =
        File(packDir(id), ".staging-${versionDir(version)}").apply {
            if (!keep) deleteRecursively()
            mkdirs()
        }

    /** Сколько байт уже лежит в staging (докачка после обрыва); 0 — ничего. */
    fun partialBytes(id: String): Long =
        packDir(id).listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(".staging-") }
            .sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    @Synchronized
    fun commit(id: String, version: String, staged: File): Installed {
        val target = File(packDir(id), versionDir(version))
        if (target.exists()) {
            // переустановка той же версии: старый каталог сначала в сторону, чтобы rename был атомарным
            val old = File(packDir(id), ".old-${versionDir(version)}-${System.nanoTime()}")
            check(target.renameTo(old)) { "не удалось убрать старую версию $target" }
        }
        check(staged.renameTo(target)) { "не удалось переименовать $staged → $target" }
        val tmp = File(packDir(id), "current.tmp")
        tmp.writeText(version)
        check(tmp.renameTo(currentFile(id))) { "не удалось переключить версию $id" }
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

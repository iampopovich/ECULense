package io.github.iampopovich.eculense.core.datapacks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Обработка скачанной версии до переключения (в каталоге staging). Например, OBDex: 30 МБ JSON → компактный индекс,
 * чтобы не разбирать JSON при каждом показе кода. Исходные файлы остаются как есть.
 */
interface PackPostProcessor {
    val packId: String
    fun process(dir: File)
}

/** Скачивание, проверка, постобработка и атомарная установка версии пакета. Без Android — тестируется на JVM. */
class PackInstaller(
    private val store: PackStore,
    private val http: PackHttp = UrlConnectionHttp(),
    private val postProcessors: List<PackPostProcessor> = emptyList(),
    private val githubBase: String = "https://github.com",
) {
    /** Последняя версия у источника; null — проверить нельзя (нет источника или сети). */
    suspend fun latestVersion(manifest: PackManifest): String? = withContext(Dispatchers.IO) {
        when (val u = manifest.update) {
            UpdateSource.None -> null
            is UpdateSource.GithubRelease -> http.redirectLocation("$githubBase/${u.repo}/releases/latest")
                ?.substringAfter("/releases/tag/", "")?.substringBefore('?')?.ifBlank { null }
            is UpdateSource.JsonField -> http.get(u.url).use { r -> jsonString(r.body.bufferedReader().readText(), u.field) }
        }
    }

    /**
     * version = null: зафиксированная версия, а для пакетов без тегов (JSON с GitHub Pages) — та, что сейчас на сервере
     * (хэш проверяется, только если она совпала с зафиксированной).
     */
    suspend fun install(manifest: PackManifest, version: String? = null, progress: DownloadProgress = DownloadProgress { _, _ -> }): PackStore.Installed =
        withContext(Dispatchers.IO) {
            val target = version ?: when (manifest.update) {
                is UpdateSource.JsonField -> runCatching { latestVersion(manifest) }.getOrNull() ?: manifest.version
                else -> manifest.version
            }
            val files = manifest.filesFor(target)
            require(files.isNotEmpty()) { "нет файлов для версии $target" }
            val staging = store.staging(manifest.id, target)
            try {
                val total = files.map { it.sizeBytes ?: return@map null }.let { l -> if (l.any { it == null }) null else l.sumOf { it!! } }
                var before = 0L
                for (f in files) {
                    val size = download(f, File(staging, f.name)) { done, _ -> progress.onProgress(before + done, total) }
                    before += size
                }
                postProcessors.filter { it.packId == manifest.id }.forEach { it.process(staging) }
                store.commit(manifest.id, target, staging)
            } catch (e: Throwable) {
                staging.deleteRecursively()
                throw e
            }
        }

    /** Пишет в `.part`, проверяет длину и sha256, затем переименовывает. Возвращает размер. */
    private suspend fun download(file: PackFile, dest: File, progress: DownloadProgress): Long {
        val part = File(dest.parentFile, dest.name + ".part")
        val md = java.security.MessageDigest.getInstance("SHA-256")
        var done = 0L
        http.get(file.url).use { r ->
            val total = file.sizeBytes ?: r.contentLength.takeIf { it > 0 }
            part.outputStream().buffered(256 * 1024).use { out ->
                val buf = ByteArray(64 * 1024)
                var lastReport = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = r.body.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    md.update(buf, 0, n)
                    done += n
                    if (done - lastReport >= 256 * 1024) {
                        lastReport = done
                        progress.onProgress(done, total)
                    }
                }
            }
            progress.onProgress(done, total)
        }
        file.sizeBytes?.let { if (it != done) throw IOException("${file.name}: ожидалось $it байт, получено $done") }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        file.sha256?.let { if (!it.equals(sha, ignoreCase = true)) throw IOException("${file.name}: контрольная сумма не совпала") }
        if (!part.renameTo(dest)) throw IOException("не удалось сохранить ${file.name}")
        return done
    }

    companion object {
        /** Строковое поле верхнего уровня без JSON-библиотеки: meta.json мал и плоский в нужном месте. */
        internal fun jsonString(json: String, field: String): String? =
            Regex("\"" + Regex.escape(field) + "\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
    }
}

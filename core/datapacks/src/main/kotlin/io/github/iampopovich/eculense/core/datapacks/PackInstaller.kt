package io.github.iampopovich.eculense.core.datapacks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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

/** Файл скачан не тот: размер больше ожидаемого или не совпал хэш. Частичный файл удаляется — докачивать нечего. */
class PackIntegrityException(message: String) : IOException(message)

/** Скачивание, проверка, постобработка и атомарная установка версии пакета. Без Android — тестируется на JVM. */
class PackInstaller(
    private val store: PackStore,
    private val http: PackHttp = UrlConnectionHttp(),
    private val postProcessors: List<PackPostProcessor> = emptyList(),
    private val githubBase: String = "https://github.com",
    /** Заголовки авторизации по URL (токен HF для gated-моделей); уходят только исходному хосту. */
    private val auth: List<PackAuth> = emptyList(),
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
     *
     * [PackManifest.resumable]: каталог staging и `.part` переживают ошибку/отмену — следующая попытка докачивает
     * (Range); при несовпадении хэша частичный файл удаляется.
     */
    suspend fun install(manifest: PackManifest, version: String? = null, progress: DownloadProgress = DownloadProgress { _, _ -> }): PackStore.Installed =
        withContext(Dispatchers.IO) {
            val target = version ?: when (manifest.update) {
                is UpdateSource.JsonField -> runCatching { latestVersion(manifest) }.getOrNull() ?: manifest.version
                else -> manifest.version
            }
            val files = manifest.filesFor(target)
            require(files.isNotEmpty()) { "нет файлов для версии $target" }
            val staging = store.staging(manifest.id, target, keep = manifest.resumable)
            try {
                val total = files.map { it.sizeBytes ?: return@map null }.let { l -> if (l.any { it == null }) null else l.sumOf { it!! } }
                var before = 0L
                for (f in files) {
                    val dest = File(staging, f.name)
                    val size = if (manifest.resumable && dest.isFile && f.sizeBytes == dest.length()) {
                        dest.length() // файл уже скачан и проверен в прошлой попытке
                    } else {
                        download(f, dest, manifest.resumable) { done, _ -> progress.onProgress(before + done, total) }
                    }
                    before += size
                }
                postProcessors.filter { it.packId == manifest.id }.forEach { it.process(staging) }
                store.commit(manifest.id, target, staging)
            } catch (e: Throwable) {
                if (!manifest.resumable || e is PackIntegrityException) staging.deleteRecursively()
                throw e
            }
        }

    private fun headersFor(url: String): Map<String, String> = auth.fold(emptyMap()) { acc, a -> acc + a.headersFor(url) }

    /** Пишет в `.part`, проверяет длину и sha256, затем переименовывает. Возвращает размер. */
    private suspend fun download(file: PackFile, dest: File, resumable: Boolean, progress: DownloadProgress): Long {
        val part = File(dest.parentFile, dest.name + ".part")
        val md = java.security.MessageDigest.getInstance("SHA-256")
        var offset = if (resumable && part.isFile) part.length() else 0L
        if (file.sizeBytes != null && offset > file.sizeBytes) offset = 0L
        val response = try {
            http.get(file.url, headersFor(file.url), offset)
        } catch (e: HttpStatusException) {
            // 416: частичный файл за концом — начать заново
            if (e.code == 416 && offset > 0) {
                offset = 0
                http.get(file.url, headersFor(file.url), 0)
            } else {
                throw e
            }
        }
        // Сервер отдал файл с начала (Range проигнорирован) — частичный файл не продолжаем.
        if (response.rangeStart == null) offset = 0
        if (offset > 0) part.inputStream().use { input ->
            // хэш уже скачанной части — чтобы проверить файл целиком без второго прохода
            val buf = ByteArray(256 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        var done = offset
        response.use { r ->
            val total = file.sizeBytes ?: r.contentLength.takeIf { it > 0 }?.let { it + offset }
            FileOutputStream(part, offset > 0).buffered(256 * 1024).use { out ->
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
        file.sizeBytes?.let {
            if (done > it) throw PackIntegrityException("${file.name}: ожидалось $it байт, получено $done")
            // недокачано: обрыв соединения — у resumable частичный файл остаётся для следующей попытки
            if (done < it) throw IOException("${file.name}: ожидалось $it байт, получено $done")
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        file.sha256?.let {
            if (!it.equals(sha, ignoreCase = true)) {
                part.delete()
                throw PackIntegrityException("${file.name}: контрольная сумма не совпала")
            }
        }
        if (!part.renameTo(dest)) throw IOException("не удалось сохранить ${file.name}")
        return done
    }

    companion object {
        /** Строковое поле верхнего уровня без JSON-библиотеки: meta.json мал и плоский в нужном месте. */
        internal fun jsonString(json: String, field: String): String? =
            Regex("\"" + Regex.escape(field) + "\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
    }
}

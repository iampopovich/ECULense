package io.github.iampopovich.eculense.core.datapacks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Processing of a downloaded version before switching (in the staging directory). E.g. OBDex: 30 MB JSON → compact index,
 * so the JSON is not parsed every time a code is shown. Source files stay as is.
 */
interface PackPostProcessor {
    val packId: String
    fun process(dir: File)
}

/** Wrong file downloaded: size larger than expected or hash mismatch. The partial file is deleted — nothing to resume. */
class PackIntegrityException(message: String) : IOException(message)

/** Download, verification, post-processing and atomic installation of a pack version. No Android — tested on the JVM. */
class PackInstaller(
    private val store: PackStore,
    private val http: PackHttp = UrlConnectionHttp(),
    private val postProcessors: List<PackPostProcessor> = emptyList(),
    private val githubBase: String = "https://github.com",
    /** Authorization headers per URL (HF token for gated models); sent only to the original host. */
    private val auth: List<PackAuth> = emptyList(),
) {
    /** Latest version at the source; null — cannot check (no source or no network). */
    suspend fun latestVersion(manifest: PackManifest): String? = withContext(Dispatchers.IO) {
        when (val u = manifest.update) {
            UpdateSource.None -> null
            is UpdateSource.GithubRelease -> http.redirectLocation("$githubBase/${u.repo}/releases/latest")
                ?.substringAfter("/releases/tag/", "")?.substringBefore('?')?.ifBlank { null }
            is UpdateSource.JsonField -> http.get(u.url).use { r -> jsonString(r.body.bufferedReader().readText(), u.field) }
        }
    }

    /**
     * version = null: the pinned version, or for untagged packs (JSON from GitHub Pages) whatever is on the server now
     * (the hash is checked only if it matches the pinned one).
     *
     * [PackManifest.resumable]: the staging directory and `.part` survive an error/cancel — the next attempt resumes
     * (Range); on hash mismatch the partial file is deleted.
     */
    suspend fun install(manifest: PackManifest, version: String? = null, progress: DownloadProgress = DownloadProgress { _, _ -> }): PackStore.Installed =
        withContext(Dispatchers.IO) {
            val target = version ?: when (manifest.update) {
                is UpdateSource.JsonField -> runCatching { latestVersion(manifest) }.getOrNull() ?: manifest.version
                else -> manifest.version
            }
            val files = manifest.filesFor(target)
            require(files.isNotEmpty()) { "no files for version $target" }
            val staging = store.staging(manifest.id, target, keep = manifest.resumable)
            try {
                val total = files.map { it.sizeBytes ?: return@map null }.let { l -> if (l.any { it == null }) null else l.sumOf { it!! } }
                var before = 0L
                for (f in files) {
                    val dest = File(staging, f.name)
                    val size = if (manifest.resumable && dest.isFile && f.sizeBytes == dest.length()) {
                        dest.length() // file already downloaded and verified in a previous attempt
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

    /** Writes to `.part`, checks length and sha256, then renames. Returns the size. */
    private suspend fun download(file: PackFile, dest: File, resumable: Boolean, progress: DownloadProgress): Long {
        val part = File(dest.parentFile, dest.name + ".part")
        val md = java.security.MessageDigest.getInstance("SHA-256")
        var offset = if (resumable && part.isFile) part.length() else 0L
        if (file.sizeBytes != null && offset > file.sizeBytes) offset = 0L
        val response = try {
            http.get(file.url, headersFor(file.url), offset)
        } catch (e: HttpStatusException) {
            // 416: partial file is past the end — start over
            if (e.code == 416 && offset > 0) {
                offset = 0
                http.get(file.url, headersFor(file.url), 0)
            } else {
                throw e
            }
        }
        // The server sent the file from the start (Range ignored) — do not continue the partial file.
        if (response.rangeStart == null) offset = 0
        if (offset > 0) part.inputStream().use { input ->
            // hash of the already downloaded part — to verify the whole file without a second pass
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
            if (done > it) throw PackIntegrityException("${file.name}: expected $it bytes, got $done")
            // incomplete: connection dropped — for resumable packs the partial file is kept for the next attempt
            if (done < it) throw IOException("${file.name}: expected $it bytes, got $done")
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        file.sha256?.let {
            if (!it.equals(sha, ignoreCase = true)) {
                part.delete()
                throw PackIntegrityException("${file.name}: checksum mismatch")
            }
        }
        if (!part.renameTo(dest)) throw IOException("could not save ${file.name}")
        return done
    }

    companion object {
        /** Top-level string field without a JSON library: meta.json is small and flat where it matters. */
        internal fun jsonString(json: String, field: String): String? =
            Regex("\"" + Regex.escape(field) + "\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
    }
}

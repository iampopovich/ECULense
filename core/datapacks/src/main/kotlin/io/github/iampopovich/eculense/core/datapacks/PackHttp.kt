package io.github.iampopovich.eculense.core.datapacks

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * GET response. contentLength = -1 if the server didn't report the length (or serves gzip).
 * [rangeStart] — the file byte the body starts at (206 Partial Content); null — the body starts at the beginning of the file.
 */
class HttpResponse(
    val code: Int,
    val contentLength: Long,
    val body: InputStream,
    private val onClose: () -> Unit = {},
    val rangeStart: Long? = null,
) : Closeable {
    override fun close() {
        runCatching { body.close() }
        onClose()
    }
}

/** HEAD response without following redirects: status code and headers (lowercase names). */
class HttpHead(val code: Int, val headers: Map<String, String>, val location: String?)

/** HTTP for packs. A separate interface — JVM tests run the real client against a local HttpServer. */
interface PackHttp {
    /** GET following redirects (github.com → release-assets.githubusercontent.com). */
    fun get(url: String): HttpResponse

    /**
     * GET with headers (only to the original host — not the CDN after a redirect) and from byte [offset] (Range). The server may
     * ignore Range and return 200 from the start — then [HttpResponse.rangeStart] = null. By default (fakes) —
     * a plain GET from the start.
     */
    fun get(url: String, headers: Map<String, String>, offset: Long): HttpResponse = get(url)

    /** HEAD without following redirects (size and hash of a Hugging Face LFS file are in `x-linked-size`/`x-linked-etag`). */
    fun head(url: String, headers: Map<String, String> = emptyMap()): HttpHead = throw UnsupportedOperationException("HEAD")

    /** Response Location without following the redirect (`/releases/latest` → `/releases/tag/<tag>`); null — no redirect. */
    fun redirectLocation(url: String): String?

    /** HTTP status of a HEAD request (200 — file exists, 404 — it doesn't). */
    fun status(url: String): Int
}

class UrlConnectionHttp(private val connectTimeoutMs: Int = 15_000, private val readTimeoutMs: Int = 30_000) : PackHttp {
    private fun connect(url: String, follow: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = follow
            setRequestProperty("User-Agent", "ECULense-datapacks")
        }

    override fun get(url: String): HttpResponse {
        val c = connect(url, follow = true)
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw IOException("HTTP $code: $url")
        }
        return HttpResponse(code, c.contentLengthLong, c.inputStream, onClose = { c.disconnect() })
    }

    /**
     * Redirects are handled manually: auth headers go only to the original URL's host (Hugging Face); the signed
     * CDN link doesn't get them. Range is passed on every hop.
     */
    override fun get(url: String, headers: Map<String, String>, offset: Long): HttpResponse {
        var current = url
        repeat(MAX_REDIRECTS) {
            val c = connect(current, follow = false)
            if (sameOrigin(current, url)) headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
            val code = c.responseCode
            when {
                code in 300..399 -> {
                    val location = c.getHeaderField("Location")
                    c.disconnect()
                    current = URL(URL(current), location ?: throw IOException("HTTP $code без Location")).toString()
                }
                code == 206 -> {
                    val range = c.getHeaderField("Content-Range")
                    val start = range?.let { parseRangeStart(it) }
                    if (start != offset) {
                        c.disconnect()
                        throw IOException("сервер вернул не тот диапазон: $range")
                    }
                    return HttpResponse(code, c.contentLengthLong, c.inputStream, onClose = { c.disconnect() }, rangeStart = start)
                }
                code in 200..299 -> return HttpResponse(code, c.contentLengthLong, c.inputStream, onClose = { c.disconnect() })
                else -> {
                    c.disconnect()
                    // only the original address in the message: the signed CDN link is long and temporary
                    throw HttpStatusException(code, url)
                }
            }
        }
        throw IOException("слишком много редиректов: $url")
    }

    override fun head(url: String, headers: Map<String, String>): HttpHead {
        val c = connect(url, follow = false)
        c.requestMethod = "HEAD"
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return try {
            val code = c.responseCode
            val all = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull().orEmpty() }
            HttpHead(code, all, c.getHeaderField("Location"))
        } finally {
            c.disconnect()
        }
    }

    override fun redirectLocation(url: String): String? {
        val c = connect(url, follow = false)
        return try {
            if (c.responseCode in 300..399) c.getHeaderField("Location") else null
        } finally {
            c.disconnect()
        }
    }

    override fun status(url: String): Int {
        val c = connect(url, follow = true)
        c.requestMethod = "HEAD"
        return try {
            c.responseCode
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 8

        internal fun sameOrigin(a: String, b: String): Boolean {
            val x = URL(a)
            val y = URL(b)
            return x.protocol == y.protocol && x.host.equals(y.host, ignoreCase = true) && x.port == y.port
        }

        /** `bytes 100-199/2588147712` → 100. */
        internal fun parseRangeStart(header: String): Long? =
            Regex("""bytes\s+(\d+)-\d+/(\d+|\*)""").find(header)?.groupValues?.get(1)?.toLongOrNull()
    }
}

/** Response with an error status (401/403 — token required, 404 — no file, 416 — range outside the file). */
class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code: $url")

/** Bytes downloaded and the total (null — unknown). */
fun interface DownloadProgress {
    fun onProgress(done: Long, total: Long?)
}

internal fun File.sha256(): String = inputStream().use { it.sha256() }

internal fun InputStream.sha256(): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

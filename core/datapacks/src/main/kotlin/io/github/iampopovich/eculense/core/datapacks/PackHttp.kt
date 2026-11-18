package io.github.iampopovich.eculense.core.datapacks

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ответ GET. contentLength = -1, если сервер не сообщил длину (или отдаёт gzip).
 * [rangeStart] — с какого байта файла начинается тело (206 Partial Content); null — тело с начала файла.
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

/** Ответ HEAD без следования редиректу: код и заголовки (имена в нижнем регистре). */
class HttpHead(val code: Int, val headers: Map<String, String>, val location: String?)

/** HTTP для пакетов. Отдельный интерфейс — JVM-тесты гоняют настоящий клиент против локального HttpServer. */
interface PackHttp {
    /** GET со следованием редиректам (github.com → release-assets.githubusercontent.com). */
    fun get(url: String): HttpResponse

    /**
     * GET с заголовками (только исходному хосту — не CDN после редиректа) и с байта [offset] (Range). Сервер вправе
     * проигнорировать Range и отдать 200 с начала — тогда [HttpResponse.rangeStart] = null. По умолчанию (фейки) —
     * обычный GET с начала.
     */
    fun get(url: String, headers: Map<String, String>, offset: Long): HttpResponse = get(url)

    /** HEAD без следования редиректу (размер и хэш LFS-файла Hugging Face — в `x-linked-size`/`x-linked-etag`). */
    fun head(url: String, headers: Map<String, String> = emptyMap()): HttpHead = throw UnsupportedOperationException("HEAD")

    /** Location ответа без следования редиректу (`/releases/latest` → `/releases/tag/<tag>`); null — редиректа нет. */
    fun redirectLocation(url: String): String?

    /** HTTP-код HEAD-запроса (200 — файл есть, 404 — нет). */
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
     * Редиректы — вручную: заголовки авторизации уходят только на хост исходного URL (Hugging Face), подписанная
     * ссылка CDN их не получает. Range передаётся на каждом шаге.
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
                    // в тексте — только исходный адрес: подписанная ссылка CDN длинная и временная
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

/** Ответ с ошибочным кодом (401/403 — нужен токен, 404 — нет файла, 416 — диапазон вне файла). */
class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code: $url")

/** Сколько байт скачано и сколько всего (null — неизвестно). */
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

package io.github.iampopovich.eculense.core.datapacks

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Ответ GET. contentLength = -1, если сервер не сообщил длину (или отдаёт gzip). */
class HttpResponse(val code: Int, val contentLength: Long, val body: InputStream, private val onClose: () -> Unit = {}) : Closeable {
    override fun close() {
        runCatching { body.close() }
        onClose()
    }
}

/** HTTP для пакетов. Отдельный интерфейс — JVM-тесты гоняют настоящий клиент против локального HttpServer. */
interface PackHttp {
    /** GET со следованием редиректам (github.com → release-assets.githubusercontent.com). */
    fun get(url: String): HttpResponse

    /** Location ответа без следования редиректу (`/releases/latest` → `/releases/tag/<tag>`); null — редиректа нет. */
    fun redirectLocation(url: String): String?
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
        return HttpResponse(code, c.contentLengthLong, c.inputStream) { c.disconnect() }
    }

    override fun redirectLocation(url: String): String? {
        val c = connect(url, follow = false)
        return try {
            if (c.responseCode in 300..399) c.getHeaderField("Location") else null
        } finally {
            c.disconnect()
        }
    }
}

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

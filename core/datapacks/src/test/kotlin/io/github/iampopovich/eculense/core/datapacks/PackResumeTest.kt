package io.github.iampopovich.eculense.core.datapacks

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

/** Докачка многогигабайтных файлов (модели ИИ): Range, сохранение `.part` между попытками, токен только своему хосту. */
class PackResumeTest {
    private lateinit var origin: HttpServer
    private lateinit var cdn: HttpServer
    private lateinit var originBase: String
    private lateinit var cdnBase: String
    private val root: File = createTempDirectory("packs").toFile()
    private val store = PackStore(root)
    private val body = ByteArray(300_000) { (it % 251).toByte() }

    /** Сколько байт CDN отдаёт до обрыва соединения (null — без обрыва). */
    private var cutAfter: Int? = null
    private var honourRange = true
    private val cdnRanges = mutableListOf<String?>()
    private val originAuth = mutableListOf<String?>()
    private val cdnAuth = mutableListOf<String?>()

    @Before
    fun start() {
        cdn = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        cdn.createContext("/blob") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            cdnRanges += range
            cdnAuth += ex.requestHeaders.getFirst("Authorization")
            val from = range?.takeIf { honourRange }?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            val slice = body.copyOfRange(from, body.size)
            if (from > 0) {
                ex.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
                ex.sendResponseHeaders(206, slice.size.toLong())
            } else {
                ex.sendResponseHeaders(200, slice.size.toLong())
            }
            val cut = cutAfter
            try {
                if (cut != null && cut < slice.size) {
                    ex.responseBody.write(slice, 0, cut)
                    ex.responseBody.flush()
                    cutAfter = null
                    // обрыв посреди ответа: соединение закрывается раньше объявленной длины
                    throw IOException("cut")
                } else {
                    ex.responseBody.write(slice)
                }
            } catch (_: IOException) {
            } finally {
                ex.close()
            }
        }
        cdn.start()
        // Другой hostname (localhost vs 127.0.0.1): токен не должен уйти на CDN.
        cdnBase = "http://localhost:${cdn.address.port}"
        origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        origin.createContext("/resolve") { ex ->
            originAuth += ex.requestHeaders.getFirst("Authorization")
            ex.responseHeaders.add("Location", "$cdnBase/blob")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        origin.start()
        originBase = "http://127.0.0.1:${origin.address.port}"
    }

    @After
    fun stop() {
        origin.stop(0)
        cdn.stop(0)
        root.deleteRecursively()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun model(sha256: String = sha(body)) = PackManifest(
        id = "model-test", title = "Тестовая модель", description = "", version = "rev1",
        files = listOf(PackFile("test.litertlm", "$originBase/resolve", body.size.toLong(), sha256)),
        license = "", licenseUrl = "", sourceUrl = "", attribution = "",
        group = PackGroup.MODEL, resumable = true,
    )

    private val auth = PackAuth { url -> if (url.startsWith(originBase)) mapOf("Authorization" to "Bearer hf_test") else emptyMap() }

    private fun installer() = PackInstaller(store, UrlConnectionHttp(), auth = listOf(auth))

    private fun part() = root.resolve("model-test").walkTopDown().firstOrNull { it.name.endsWith(".part") }

    @Test
    fun `interrupted download keeps the part and the next attempt resumes with Range`() = runTest {
        cutAfter = 120_000
        assertTrue(runCatching { installer().install(model()) }.exceptionOrNull() is IOException)
        val kept = part()
        assertTrue("частичный файл остаётся для докачки", kept != null && kept.length() > 0)
        val already = kept!!.length()

        val installed = installer().install(model())
        assertEquals("rev1", installed.version)
        assertTrue(body.contentEquals(store.file("model-test", "test.litertlm")!!.readBytes()))
        assertEquals("bytes=$already-", cdnRanges.last())
        assertNull(part())
    }

    @Test
    fun `server ignoring Range restarts from zero and still verifies`() = runTest {
        cutAfter = 50_000
        runCatching { installer().install(model()) }
        honourRange = false
        installer().install(model())
        assertTrue(body.contentEquals(store.file("model-test", "test.litertlm")!!.readBytes()))
    }

    @Test
    fun `checksum mismatch drops the part so the next attempt starts clean`() = runTest {
        assertTrue(runCatching { installer().install(model(sha256 = "00".repeat(32))) }.exceptionOrNull() is IOException)
        assertNull(part())
        assertNull(store.installed("model-test"))
    }

    @Test
    fun `token goes only to the origin host, never to the redirect target`() = runTest {
        installer().install(model())
        assertEquals("Bearer hf_test", originAuth.single())
        assertNull(cdnAuth.single())
    }

    @Test
    fun `non-resumable packs still start clean`() = runTest {
        cutAfter = 120_000
        val plain = model().copy(resumable = false, group = PackGroup.REFERENCE)
        runCatching { installer().install(plain) }
        assertNull(part())
        assertFalse(root.resolve("model-test").listFiles().orEmpty().any { it.name.startsWith(".staging") })
    }
}

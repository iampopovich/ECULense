package io.github.iampopovich.eculense.core.datapacks

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress

/** The real [UrlConnectionHttp] against a loopback JDK HttpServer: status codes, redirects, Range, HEAD headers. */
class UrlConnectionHttpTest {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val base = "http://127.0.0.1:${server.address.port}"
    private val body = ByteArray(1_000) { (it % 251).toByte() }
    @Volatile private var lastHeaders: Map<String, String?> = emptyMap()

    private fun HttpExchange.reply(code: Int, bytes: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> responseHeaders.add(k, v) }
        sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) } else close()
    }

    init {
        server.createContext("/file") { ex ->
            lastHeaders = mapOf("Authorization" to ex.requestHeaders.getFirst("Authorization"), "Range" to ex.requestHeaders.getFirst("Range"))
            val range = ex.requestHeaders.getFirst("Range")
            if (ex.requestMethod == "HEAD") {
                ex.reply(200, headers = mapOf("x-linked-size" to "1000", "x-linked-etag" to "\"abc\""))
            } else if (range != null) {
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                ex.reply(206, body.copyOfRange(from, body.size), mapOf("Content-Range" to "bytes $from-${body.size - 1}/${body.size}"))
            } else {
                ex.reply(200, body)
            }
        }
        server.createContext("/moved") { ex -> ex.reply(302, headers = mapOf("Location" to "/file")) }
        server.createContext("/loop") { ex -> ex.reply(302, headers = mapOf("Location" to "/loop")) }
        server.createContext("/badrange") { ex -> ex.reply(206, body, mapOf("Content-Range" to "bytes 0-999/1000")) }
        server.createContext("/nolocation") { ex -> ex.reply(302) }
        server.createContext("/gone") { ex -> ex.reply(404) }
        server.createContext("/token") { ex -> ex.reply(401) }
        server.createContext("/range416") { ex -> ex.reply(416) }
        server.createContext("/releases/latest") { ex -> ex.reply(302, headers = mapOf("Location" to "$base/releases/tag/v1.2.3")) }
        server.start()
    }

    @After fun tearDown() = server.stop(0)

    private val http = UrlConnectionHttp(connectTimeoutMs = 2_000, readTimeoutMs = 2_000)

    @Test
    fun `plain GET follows redirects and reads the body, an error status is an IOException`() {
        http.get("$base/moved").use { r ->
            assertEquals(200, r.code)
            assertEquals(1_000L, r.contentLength)
            assertTrue(body.contentEquals(r.body.readBytes()))
            assertNull(r.rangeStart)
        }
        try {
            http.get("$base/gone")
            fail("404 must throw")
        } catch (e: IOException) {
            assertTrue(e.message!!, "HTTP 404" in e.message!!)
        }
    }

    @Test
    fun `ranged GET resumes from the offset and checks the server's Content-Range`() {
        http.get("$base/file", emptyMap(), offset = 600).use { r ->
            assertEquals(206, r.code)
            assertEquals(600L, r.rangeStart)
            assertEquals(400, r.body.readBytes().size)
        }
        assertEquals("bytes=600-", lastHeaders["Range"])
        try {
            http.get("$base/badrange", emptyMap(), offset = 600)
            fail("a range that does not start at the offset must be refused")
        } catch (e: IOException) {
            assertTrue(e.message!!, "wrong range" in e.message!!)
        }
    }

    @Test
    fun `error statuses carry their code, missing Location and redirect loops fail`() {
        for ((path, code) in listOf("/gone" to 404, "/token" to 401, "/range416" to 416)) {
            try {
                http.get("$base$path", mapOf("Authorization" to "Bearer test-token"), 0)
                fail("$path must throw")
            } catch (e: HttpStatusException) {
                assertEquals(code, e.code)
                assertFalse("the message has no headers", "test-token" in e.message!!)
            }
        }
        try {
            http.get("$base/nolocation", emptyMap(), 0)
            fail("302 without Location")
        } catch (e: IOException) {
            assertTrue(e.message!!, "without Location" in e.message!!)
        }
        try {
            http.get("$base/loop", emptyMap(), 0)
            fail("redirect loop")
        } catch (e: IOException) {
            assertTrue(e.message!!, "too many redirects" in e.message!!)
        }
    }

    @Test
    fun `headers go to the same origin only`() {
        http.get("$base/moved", mapOf("Authorization" to "Bearer test-token"), 0).close()
        assertEquals("Bearer test-token", lastHeaders["Authorization"]) // same host:port after the redirect
        assertTrue(UrlConnectionHttp.sameOrigin("$base/a", "$base/b"))
        assertFalse(UrlConnectionHttp.sameOrigin("https://huggingface.co/x", "https://cdn-lfs.huggingface.co/x"))
        assertFalse(UrlConnectionHttp.sameOrigin("http://127.0.0.1:1/a", "http://127.0.0.1:2/a"))
    }

    @Test
    fun `HEAD returns lowercase headers, redirectLocation and status do not follow or throw`() {
        val head = http.head("$base/file")
        assertEquals(200, head.code)
        assertEquals("1000", head.headers["x-linked-size"])
        assertEquals("\"abc\"", head.headers["x-linked-etag"])
        assertEquals("$base/releases/tag/v1.2.3", http.redirectLocation("$base/releases/latest"))
        assertNull(http.redirectLocation("$base/file"))
        assertEquals(200, http.status("$base/file"))
        assertEquals(404, http.status("$base/gone"))
    }

    @Test
    fun `Content-Range parsing`() {
        assertEquals(100L, UrlConnectionHttp.parseRangeStart("bytes 100-199/2588147712"))
        assertEquals(0L, UrlConnectionHttp.parseRangeStart("bytes 0-0/*"))
        assertNull(UrlConnectionHttp.parseRangeStart("items 1-2/3"))
    }
}

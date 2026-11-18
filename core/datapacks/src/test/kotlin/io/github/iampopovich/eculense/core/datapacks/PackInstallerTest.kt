package io.github.iampopovich.eculense.core.datapacks

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

class PackInstallerTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    private val root: File = createTempDirectory("packs").toFile()
    private val store = PackStore(root)
    private val files = mutableMapOf<String, ByteArray>()
    private var latestTag = "v2"
    private var metaVersion = "2026-01-01T00:00:00Z"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            when {
                path.endsWith("/releases/latest") -> {
                    ex.responseHeaders.add("Location", "$base/owner/repo/releases/tag/$latestTag")
                    ex.sendResponseHeaders(302, -1)
                }
                path == "/meta.json" -> {
                    val body = """{"generated_at": "$metaVersion", "license": "CC0-1.0"}""".toByteArray()
                    ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.write(body)
                }
                files.containsKey(path) -> {
                    val body = files.getValue(path)
                    ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.write(body)
                }
                else -> ex.sendResponseHeaders(404, -1)
            }
            ex.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun stop() {
        server.stop(0)
        root.deleteRecursively()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun release(tagged: Map<String, ByteArray>, pinned: String = "v1", sha: String? = null) = PackManifest(
        id = "db", title = "Тестовый пакет", description = "", version = pinned,
        files = listOf(PackFile("data.db", "$base/dl/$pinned/data.db", tagged.getValue(pinned).size.toLong(), sha ?: sha(tagged.getValue(pinned)))),
        license = "MIT", licenseUrl = "", sourceUrl = "", attribution = "",
        update = UpdateSource.GithubRelease("owner/repo", mapOf("data.db" to "$base/dl/{tag}/data.db")),
    ).also { tagged.forEach { (tag, bytes) -> files["/dl/$tag/data.db"] = bytes } }

    private fun installer(post: List<PackPostProcessor> = emptyList()) = PackInstaller(store, UrlConnectionHttp(), post, githubBase = base)

    @Test
    fun `install downloads, verifies and activates the pinned version`() = runTest {
        val m = release(mapOf("v1" to "первая".toByteArray()))
        val progress = mutableListOf<Pair<Long, Long?>>()
        val installed = installer().install(m) { done, total -> progress += done to total }
        assertEquals("v1", installed.version)
        assertEquals("первая", store.file("db", "data.db")!!.readText())
        assertEquals("v1", store.installed("db")!!.version)
        assertEquals(progress.last().first, progress.last().second)
        assertFalse(File(installed.dir, "data.db.part").exists())
    }

    @Test
    fun `checksum mismatch installs nothing and keeps the previous version`() = runTest {
        val good = release(mapOf("v1" to "первая".toByteArray()))
        installer().install(good)
        val bad = release(mapOf("v1" to "первая".toByteArray()), sha = "00".repeat(32)).copy(version = "v1")
        try {
            installer().install(bad.copy(id = "db"), version = null)
            fail("должна быть ошибка контрольной суммы")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("контрольная сумма"))
        }
        assertEquals("первая", store.file("db", "data.db")!!.readText())
        assertEquals(listOf("current", "v1"), root.resolve("db").list()!!.sorted())
    }

    @Test
    fun `update to a newer release switches the version and removes the old one`() = runTest {
        val m = release(mapOf("v1" to "первая".toByteArray(), "v2" to "вторая".toByteArray()))
        installer().install(m)
        assertEquals("v2", installer().latestVersion(m))
        store.setAvailableUpdate("db", "v2")

        installer().install(m, version = "v2")
        assertEquals("вторая", store.file("db", "data.db")!!.readText())
        assertEquals(listOf("current", "v2"), root.resolve("db").list()!!.sorted())
        assertNull("установленное обновление больше не «доступно»", store.availableUpdate("db"))
    }

    @Test
    fun `pages json pack follows the server version and checks the hash only for the pinned one`() = runTest {
        val bytes = "[]".toByteArray()
        files["/generic.json"] = bytes
        val m = PackManifest(
            id = "obdex", title = "", description = "", version = "2026-01-01T00:00:00Z",
            files = listOf(PackFile("generic.json", "$base/generic.json", bytes.size.toLong(), sha(bytes))),
            license = "CC0", licenseUrl = "", sourceUrl = "", attribution = "",
            update = UpdateSource.JsonField("$base/meta.json", "generated_at"),
        )
        assertEquals("2026-01-01T00:00:00Z", installer().install(m).version)

        metaVersion = "2026-02-01T00:00:00Z"
        files["/generic.json"] = "[1]".toByteArray()
        val updated = installer().install(m)
        assertEquals("2026-02-01T00:00:00Z", updated.version)
        assertEquals("[1]", store.file("obdex", "generic.json")!!.readText())
        assertEquals("2026-02-01T00_00_00Z", updated.dir.name)
    }

    @Test
    fun `post-processor runs before activation and its failure installs nothing`() = runTest {
        val m = release(mapOf("v1" to "первая".toByteArray()))
        val index = object : PackPostProcessor {
            override val packId = "db"
            override fun process(dir: File) = File(dir, "index.tsv").writeText(File(dir, "data.db").readText().uppercase())
        }
        installer(listOf(index)).install(m)
        assertEquals("ПЕРВАЯ", store.file("db", "index.tsv")!!.readText())

        store.delete("db")
        val broken = object : PackPostProcessor {
            override val packId = "db"
            override fun process(dir: File) = throw IllegalStateException("битый файл")
        }
        runCatching { installer(listOf(broken)).install(m) }
        assertNull(store.installed("db"))
        assertTrue(root.resolve("db").listFiles().orEmpty().none { it.name.startsWith(".staging") })
    }

    @Test
    fun `missing file on the server is an error`() = runTest {
        val m = release(mapOf("v1" to "x".toByteArray()))
        files.clear()
        assertTrue(runCatching { installer().install(m) }.exceptionOrNull() is IOException)
        assertNull(store.installed("db"))
    }

    @Test
    fun `delete removes the pack`() = runTest {
        installer().install(release(mapOf("v1" to "x".toByteArray())))
        store.delete("db")
        assertNull(store.installed("db"))
        assertNull(store.file("db", "data.db"))
    }
}

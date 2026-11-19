package io.github.iampopovich.eculense.core.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.sql.DriverManager
import java.util.zip.ZipInputStream

/** ECULense backup on synthetic data: SQLite via sqlite-jdbc, settings are real DataStore files. */
class EcuBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private val apiKey = stringPreferencesKey("api_key")
    private val provider = stringPreferencesKey("provider")
    private val hfToken = stringPreferencesKey("hf_token")
    private val activeCar = longPreferencesKey("active_car_id")
    private val celsius = booleanPreferencesKey("units_celsius")

    private class JdbcDb(private val file: File) : DbSource {
        override val fileName: String = file.name
        override fun snapshotTo(target: File): Int {
            if (!file.exists()) return -1
            DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
                c.createStatement().use { it.execute("VACUUM INTO '${target.path.replace("'", "''")}'") }
                return c.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
            }
        }
    }

    private fun sql(file: File, vararg statements: String) = DriverManager.getConnection("jdbc:sqlite:${file.also { it.parentFile.mkdirs() }.path}").use { c ->
        c.createStatement().use { st -> statements.forEach { st.execute(it) } }
    }

    private fun count(file: File, table: String): Int = DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
        c.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM $table").use { it.next(); it.getInt(1) } }
    }

    private fun prefs(dir: File, name: String, block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        PreferenceDataStoreFactory.create(scope = scope) { File(dir, "$name.preferences_pb") }.edit { block(it) }
        scope.cancel()
    }

    private fun read(dir: File, name: String): Preferences = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        PreferenceDataStoreFactory.create(scope = scope) { File(dir, "$name.preferences_pb") }.data.first().also { scope.cancel() }
    }

    /** Phone "A": v6 database with recordings/readings/trips, settings with an API key and an HF token. */
    private fun deviceA(): Triple<File, File, File> {
        val root = tmp.newFolder("a")
        val db = File(root, "databases/ecu.db").apply { parentFile.mkdirs() }
        sql(
            db,
            "CREATE TABLE sessions (id INTEGER PRIMARY KEY, name TEXT)",
            "CREATE TABLE readings (id INTEGER PRIMARY KEY, sessionId INTEGER, value REAL)",
            "CREATE TABLE trips (id INTEGER PRIMARY KEY, distanceMm INTEGER)",
            "INSERT INTO sessions (name) VALUES ('Тестовая 1'), ('Тестовая 2')",
            "INSERT INTO readings (sessionId, value) VALUES (1, 800), (1, 900), (2, 750)",
            "INSERT INTO trips (distanceMm) VALUES (12000000), (5000000), (8000000), (1000000)",
            "PRAGMA user_version = 6",
        )
        val chat = File(root, "databases/ai_chat.db")
        sql(chat, "CREATE TABLE messages (id INTEGER PRIMARY KEY, text TEXT)", "INSERT INTO messages (text) VALUES ('Что по ТО?')", "PRAGMA user_version = 1")
        val store = File(root, "datastore").apply { mkdirs() }
        prefs(store, "vehicle") { it[activeCar] = 2; it[celsius] = false }
        prefs(store, "llm_settings") { it[provider] = "OPENROUTER"; it[apiKey] = "sk-test-secret-value" }
        prefs(store, "ailocal") { it[hfToken] = "hf_test_secret_token" }
        return Triple(db, chat, store)
    }

    private fun backup(root: File, db: File, chat: File, store: File, schema: Int = 6) = EcuBackup(
        databases = listOf(JdbcDb(db), JdbcDb(chat)),
        databaseTargets = mapOf("ecu.db" to db, "ai_chat.db" to chat),
        dataStoreDir = store,
        workDir = File(root, "cache").apply { mkdirs() },
        schemaVersion = schema,
        appVersion = "0.1.0-test",
        closeDatabases = {},
        clock = { 1_767_000_000_000L },
    )

    private fun entries(zip: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> while (true) { val e = z.nextEntry ?: break; put(e.name, z.readBytes()) } }
    }

    @Test
    fun `backup holds the database, settings and manifest but no secrets`() {
        val (db, chat, store) = deviceA()
        val out = ByteArrayOutputStream()
        val manifest = backup(db.parentFile.parentFile, db, chat, store).export(out, includeAiChat = false)

        val zip = out.toByteArray()
        val files = entries(zip)
        assertEquals(setOf("manifest.json", "databases/ecu.db", "datastore/llm_settings.preferences_pb", "datastore/vehicle.preferences_pb"), files.keys)
        assertEquals(6, manifest.schemaVersion)
        assertEquals("0.1.0-test", manifest.appVersion)
        assertEquals(1, manifest.secretsExcluded)
        // neither the key/token values nor the keys themselves appear in any archive file (zip + extracted files)
        for ((name, bytes) in files + ("<zip>" to zip)) {
            val text = String(bytes, Charsets.ISO_8859_1)
            assertFalse(name, text.contains("sk-test-secret-value"))
            assertFalse(name, text.contains("hf_test_secret_token"))
            assertFalse(name, text.contains("api_key"))
            assertFalse(name, text.contains("hf_token"))
        }
        assertEquals(listOf("provider"), PrefsFile.keys(files.getValue("datastore/llm_settings.preferences_pb")))
    }

    @Test
    fun `export then restore on a clean device gives the same counts`() {
        val (db, chat, store) = deviceA()
        val out = ByteArrayOutputStream()
        backup(db.parentFile.parentFile, db, chat, store).export(out, includeAiChat = true)

        val b = tmp.newFolder("b")
        val bDb = File(b, "databases/ecu.db")
        val bChat = File(b, "databases/ai_chat.db")
        val bStore = File(b, "datastore")
        val result = backup(b, bDb, bChat, bStore).restore(ByteArrayInputStream(out.toByteArray()))

        assertNull(result.migratesFrom)
        assertEquals(2, count(bDb, "sessions"))
        assertEquals(3, count(bDb, "readings"))
        assertEquals(4, count(bDb, "trips"))
        assertEquals(1, count(bChat, "messages"))
        assertEquals(2L, read(bStore, "vehicle")[activeCar])
        assertEquals(false, read(bStore, "vehicle")[celsius])
        assertEquals("OPENROUTER", read(bStore, "llm_settings")[provider])
        assertNull(read(bStore, "llm_settings")[apiKey])
        assertFalse("ailocal не переносится", File(bStore, "ailocal.preferences_pb").exists())
    }

    @Test
    fun `restore keeps the secrets of this phone and replaces the old WAL`() {
        val (db, chat, store) = deviceA()
        val out = ByteArrayOutputStream()
        backup(db.parentFile.parentFile, db, chat, store).export(out, includeAiChat = false)

        val b = tmp.newFolder("b")
        val bDb = File(b, "databases/ecu.db")
        sql(bDb, "CREATE TABLE sessions (id INTEGER PRIMARY KEY, name TEXT)", "INSERT INTO sessions (name) VALUES ('старая')")
        File(bDb.path + "-wal").writeText("stale")
        val bStore = File(b, "datastore").apply { mkdirs() }
        prefs(bStore, "llm_settings") { it[provider] = "DEEPSEEK"; it[apiKey] = "sk-this-phone" }

        backup(b, bDb, File(b, "databases/ai_chat.db"), bStore).restore(ByteArrayInputStream(out.toByteArray()))

        assertEquals(2, count(bDb, "sessions"))
        assertFalse(File(bDb.path + "-wal").exists() && File(bDb.path + "-wal").readText() == "stale")
        val llm = read(bStore, "llm_settings")
        assertEquals("OPENROUTER", llm[provider])
        assertEquals("sk-this-phone", llm[apiKey])
    }

    @Test
    fun `backup from a newer schema is rejected and current data stays`() {
        val (db, chat, store) = deviceA()
        sql(db, "PRAGMA user_version = 7")
        val out = ByteArrayOutputStream()
        backup(db.parentFile.parentFile, db, chat, store, schema = 7).export(out, includeAiChat = false)

        val b = tmp.newFolder("b")
        val bDb = File(b, "databases/ecu.db")
        sql(bDb, "CREATE TABLE sessions (id INTEGER PRIMARY KEY, name TEXT)", "INSERT INTO sessions (name) VALUES ('своя')")
        var closed = false
        val restore = EcuBackup(emptyList(), mapOf("ecu.db" to bDb), File(b, "datastore"), File(b, "cache"), 6, "0.1.0-test", { closed = true })

        val e = assertThrows(BackupRejectedException::class.java) { restore.restore(ByteArrayInputStream(out.toByteArray())) }
        assertTrue(e.problem.toString(), e.problem is BackupProblem.NewerVersion)
        assertFalse(closed)
        assertEquals(1, count(bDb, "sessions"))
    }

    @Test
    fun `older schema is accepted and left to Room migrations`() {
        val (db, chat, store) = deviceA()
        sql(db, "PRAGMA user_version = 5")
        val out = ByteArrayOutputStream()
        backup(db.parentFile.parentFile, db, chat, store, schema = 5).export(out, includeAiChat = false)
        val b = tmp.newFolder("b")
        val result = backup(b, File(b, "ecu.db"), File(b, "ai_chat.db"), File(b, "datastore"))
            .restore(ByteArrayInputStream(out.toByteArray()))
        assertEquals(5, result.migratesFrom)
        assertEquals(5, EcuBackup.sqliteUserVersion(File(b, "ecu.db")))
    }

    @Test
    fun `random zip is not a backup`() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { it.putNextEntry(java.util.zip.ZipEntry("../evil.txt")); it.write(1); it.closeEntry() }
        val b = tmp.newFolder("b")
        assertThrows(BackupRejectedException::class.java) {
            backup(b, File(b, "ecu.db"), File(b, "ai_chat.db"), File(b, "datastore")).inspect(ByteArrayInputStream(out.toByteArray()))
        }
        assertFalse(File(b.parentFile, "evil.txt").exists())
    }
}

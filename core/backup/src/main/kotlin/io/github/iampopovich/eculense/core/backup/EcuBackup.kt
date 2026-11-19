package io.github.iampopovich.eculense.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** `.eculense` manifest: restore uses it to decide whether the backup can be accepted. */
@Serializable
data class BackupManifest(
    val format: Int,
    val app: String,
    val appVersion: String,
    val schemaVersion: Int,
    val createdAtMs: Long,
    val databases: List<String>,
    val datastore: List<String>,
    /** How many secret values (API keys, tokens) were excluded — just the count, no names or values. */
    val secretsExcluded: Int,
)

/** Database for the backup: snapshotTo writes a consistent copy (WAL checkpoint + VACUUM INTO) and returns user_version. */
interface DbSource {
    val fileName: String
    fun snapshotTo(target: File): Int
}

class BackupRejectedException(message: String) : Exception(message)

data class RestoreResult(val manifest: BackupManifest, val migratesFrom: Int?)

/**
 * ECULense backup (1.27 item 4): zip `.eculense` = manifest.json + databases/ecu.db (+ ai_chat.db if flagged) +
 * datastore/<file>.preferences_pb without secrets. Data packs and models are not included (re-downloaded); API keys and
 * tokens — never (they stay on the phone; on restore the current secrets are kept).
 *
 * Restore: check the manifest and schema version (newer — reject, older — Room migrates on startup),
 * close the database, replace files (also deleting the old database's -wal/-shm), then the app restarts.
 */
class EcuBackup(
    private val databases: List<DbSource>,
    /** Where to restore databases: file name → path (ecu.db, ai_chat.db). */
    private val databaseTargets: Map<String, File>,
    private val dataStoreDir: File,
    private val workDir: File,
    private val schemaVersion: Int,
    private val appVersion: String,
    /** Close open databases before replacing files. */
    private val closeDatabases: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun export(out: OutputStream, includeAiChat: Boolean): BackupManifest {
        val staging = File(workDir, "backup-export").apply { deleteRecursively(); mkdirs() }
        try {
            val dbFiles = mutableListOf<File>()
            var version = -1
            for (db in databases) {
                if (db.fileName == AI_CHAT_DB && !includeAiChat) continue
                val target = File(staging, db.fileName)
                val v = runCatching { db.snapshotTo(target) }.getOrElse { e ->
                    if (db.fileName == MAIN_DB) throw e
                    -1 // AI chat not created yet — backup without it
                }
                if (v < 0 || !target.exists()) continue
                if (db.fileName == MAIN_DB) version = v
                dbFiles += target
            }
            check(version >= 0) { "нет базы $MAIN_DB" }
            var secrets = 0
            val prefs = mutableListOf<Pair<String, ByteArray>>()
            dataStoreDir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(PREFS_SUFFIX) && it.name.removeSuffix(PREFS_SUFFIX) !in EXCLUDED_STORES }
                .sortedBy { it.name }
                .forEach { f ->
                    val split = runCatching { PrefsFile.split(f.readBytes()) }.getOrNull() ?: return@forEach // unknown file — skip it
                    secrets += split.secretCount
                    prefs += f.name to split.public
                }
            val manifest = BackupManifest(
                format = FORMAT, app = APP, appVersion = appVersion, schemaVersion = version, createdAtMs = clock(),
                databases = dbFiles.map { it.name }, datastore = prefs.map { it.first }, secretsExcluded = secrets,
            )
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray()); zip.closeEntry()
                dbFiles.forEach { f -> zip.putNextEntry(ZipEntry("$DB_DIR/${f.name}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                prefs.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry("$PREFS_DIR/$name")); zip.write(bytes); zip.closeEntry() }
            }
            return manifest
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Unpacks and validates the backup without touching current data. */
    fun inspect(input: InputStream): BackupManifest = unpack(input).let { (manifest, dir) -> dir.deleteRecursively(); manifest }

    fun restore(input: InputStream): RestoreResult {
        val (manifest, dir) = unpack(input)
        try {
            closeDatabases()
            for (name in manifest.databases) {
                val target = databaseTargets[name] ?: continue
                target.parentFile?.mkdirs()
                SQLITE_SIDE_FILES.forEach { File(target.path + it).delete() }
                File(dir, "$DB_DIR/$name").copyTo(target, overwrite = true)
            }
            dataStoreDir.mkdirs()
            for (name in manifest.datastore) {
                val current = File(dataStoreDir, name)
                // secrets are excluded from the backup — keep the current ones (the API key stays on this phone)
                val keepSecrets = if (current.exists()) runCatching { PrefsFile.split(current.readBytes()).secret }.getOrDefault(ByteArray(0)) else ByteArray(0)
                val restored = File(dir, "$PREFS_DIR/$name").readBytes()
                current.writeBytes(restored + keepSecrets)
            }
            return RestoreResult(manifest, manifest.schemaVersion.takeIf { it < schemaVersion })
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun unpack(input: InputStream): Pair<BackupManifest, File> {
        val dir = File(workDir, "backup-restore").apply { deleteRecursively(); mkdirs() }
        try {
            var manifestBytes: ByteArray? = null
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name
                    val allowed = name == MANIFEST ||
                        (name.startsWith("$DB_DIR/") && name.substringAfter('/') in KNOWN_DATABASES) ||
                        (name.startsWith("$PREFS_DIR/") && SAFE_NAME.matches(name.substringAfter('/')) && name.endsWith(PREFS_SUFFIX))
                    if (!allowed) continue // skip foreign and unsafe paths (../)
                    if (name == MANIFEST) manifestBytes = zip.readBytes()
                    else File(dir, name).apply { parentFile?.mkdirs() }.outputStream().use { zip.copyTo(it) }
                }
            }
            val manifest = manifestBytes?.let { runCatching { json.decodeFromString(BackupManifest.serializer(), it.decodeToString()) }.getOrNull() }
                ?: throw BackupRejectedException("Это не резервная копия ECULense")
            if (manifest.app != APP || manifest.format > FORMAT) throw BackupRejectedException("Неизвестный формат копии")
            if (manifest.schemaVersion > schemaVersion) {
                throw BackupRejectedException("Копия сделана более новой версией приложения (схема ${manifest.schemaVersion}, у этой версии $schemaVersion). Обновите ECULense.")
            }
            val db = File(dir, "$DB_DIR/$MAIN_DB")
            if (MAIN_DB !in manifest.databases || !db.exists()) throw BackupRejectedException("В копии нет базы данных")
            if (sqliteUserVersion(db) != manifest.schemaVersion) throw BackupRejectedException("Копия повреждена: версия базы не совпадает с манифестом")
            manifest.datastore.forEach { name ->
                val f = File(dir, "$PREFS_DIR/$name")
                if (!f.exists() || runCatching { PrefsFile.split(f.readBytes()).secretCount }.getOrDefault(1) != 0) {
                    throw BackupRejectedException("Копия повреждена: настройки $name")
                }
            }
            return manifest to dir
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    companion object {
        const val FORMAT = 1
        const val APP = "io.github.iampopovich.eculense"
        const val MAIN_DB = "ecu.db"
        const val AI_CHAT_DB = "ai_chat.db"
        const val EXTENSION = "eculense"
        private const val MANIFEST = "manifest.json"
        private const val DB_DIR = "databases"
        private const val PREFS_DIR = "datastore"
        private const val PREFS_SUFFIX = ".preferences_pb"
        private val KNOWN_DATABASES = setOf(MAIN_DB, AI_CHAT_DB)
        private val SAFE_NAME = Regex("[A-Za-z0-9_.-]+")
        private val SQLITE_SIDE_FILES = listOf("-wal", "-shm", "-journal")

        /**
         * Stores that don't go into the backup: ailocal — the downloaded model choice and this phone's benchmarks (models
         * are not copied), backup — SAF access to the backups folder (the permission is valid only on this device).
         */
        val EXCLUDED_STORES = setOf("ailocal", "backup")
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        /** PRAGMA user_version from the SQLite header (offset 60, big-endian) — without opening the database. */
        fun sqliteUserVersion(file: File): Int = RandomAccessFile(file, "r").use { f ->
            val magic = ByteArray(16).also { f.readFully(it) }
            if (!magic.decodeToString().startsWith("SQLite format 3")) throw BackupRejectedException("Копия повреждена: база не SQLite")
            f.seek(60)
            f.readInt()
        }
    }
}

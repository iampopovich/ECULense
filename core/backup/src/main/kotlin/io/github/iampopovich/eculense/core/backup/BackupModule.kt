package io.github.iampopovich.eculense.core.backup

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.iampopovich.eculense.core.data.CarScannerImporter
import io.github.iampopovich.eculense.core.database.EcuDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File
import kotlin.system.exitProcess

/** The app's own database, via the Room connection: WAL checkpoint and VACUUM INTO without a second writer. */
internal class RoomDbSource(private val db: () -> EcuDatabase) : DbSource {
    override val fileName = EcuDatabase.FILE_NAME
    override fun snapshotTo(target: File): Int {
        val sdb = db().openHelper.writableDatabase
        target.delete()
        sdb.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        if (Build.VERSION.SDK_INT >= 30) {
            sdb.execSQL("VACUUM INTO '${target.path.replace("'", "''")}'") // SQLite ≥ 3.27 (Android 11+)
        } else {
            // old SQLite without VACUUM INTO: after checkpoint(TRUNCATE) the main file contains everything
            sdb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            File(sdb.path ?: error("database has no path")).copyTo(target, overwrite = true)
        }
        return EcuBackup.sqliteUserVersion(target)
    }
}

/** A database foreign to this module (AI chat history from :core:ai): read-only, we don't touch the Room journal. */
internal class FileDbSource(private val file: File) : DbSource {
    override val fileName: String = file.name
    override fun snapshotTo(target: File): Int {
        if (!file.exists()) return -1
        target.delete()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            if (Build.VERSION.SDK_INT >= 30) db.execSQL("VACUUM INTO '${target.path.replace("'", "''")}'") else return -1
        }
        return EcuBackup.sqliteUserVersion(target)
    }
}

/** Car Scanner backup folder (SAF tree with persistent access) and the «AI chat history» flag for the ECULense copy. */
class BackupPrefs(private val store: DataStore<Preferences>) {
    val folderUri: Flow<String?> = store.data.map { it[FOLDER] }
    val includeAiChat: Flow<Boolean> = store.data.map { it[AI_CHAT] ?: false }

    suspend fun setFolder(uri: String?) {
        store.edit { if (uri == null) it.remove(FOLDER) else it[FOLDER] = uri }
    }

    suspend fun setIncludeAiChat(value: Boolean) {
        store.edit { it[AI_CHAT] = value }
    }

    private companion object {
        val FOLDER = stringPreferencesKey("carscanner_folder")
        val AI_CHAT = booleanPreferencesKey("include_ai_chat")
    }
}

/** ECULense copy via SAF: Uri from «Save as» / the picked file. */
interface BackupService {
    suspend fun export(uri: Uri, includeAiChat: Boolean): BackupManifest
    suspend fun inspect(uri: Uri): BackupManifest
    suspend fun restore(uri: Uri): RestoreResult
    /** Restart after restore: database and settings have changed, the process starts over. */
    fun restartApp()
}

internal class AndroidBackupService(private val context: Context, private val backup: EcuBackup) : BackupService {
    override suspend fun export(uri: Uri, includeAiChat: Boolean): BackupManifest = withContext(Dispatchers.IO) {
        (context.contentResolver.openOutputStream(uri, "wt") ?: throw BackupRejectedException(BackupProblem.CannotCreateFile)).use { backup.export(it, includeAiChat) }
    }

    override suspend fun inspect(uri: Uri): BackupManifest = withContext(Dispatchers.IO) {
        (context.contentResolver.openInputStream(uri) ?: throw BackupRejectedException(BackupProblem.CannotOpenFile)).use { backup.inspect(it) }
    }

    override suspend fun restore(uri: Uri): RestoreResult = withContext(Dispatchers.IO) {
        (context.contentResolver.openInputStream(uri) ?: throw BackupRejectedException(BackupProblem.CannotOpenFile)).use { backup.restore(it) }
    }

    override fun restartApp() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(launch.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        exitProcess(0)
    }
}

val backupModule = module {
    single { BackupPrefs(PreferenceDataStoreFactory.create { androidContext().preferencesDataStoreFile("backup") }) }
    single {
        val importer = get<CarScannerImporter>()
        ImportRunner({ uri, name, onProgress -> importer.import(Uri.parse(uri), name, onProgress) }, get())
    }
    single<ImportService> { CarScannerImports(androidContext()) }
    single {
        val context = androidContext()
        val koin = getKoin()
        val chat = context.getDatabasePath(EcuBackup.AI_CHAT_DB)
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        EcuBackup(
            databases = listOf(RoomDbSource { koin.get() }, FileDbSource(chat)),
            databaseTargets = mapOf(EcuBackup.MAIN_DB to context.getDatabasePath(EcuDatabase.FILE_NAME), EcuBackup.AI_CHAT_DB to chat),
            dataStoreDir = File(context.filesDir, "datastore"),
            workDir = context.cacheDir,
            schemaVersion = EcuDatabase.VERSION,
            appVersion = version,
            closeDatabases = { runCatching { koin.get<EcuDatabase>().close() } },
            accelDir = File(context.filesDir, "accel"),
        )
    }
    single<BackupService> { AndroidBackupService(androidContext(), get()) }
    single<StorageService> { AndroidStorageService(androidContext(), getOrNull<io.github.iampopovich.eculense.core.data.SessionRecorder>()) }
}

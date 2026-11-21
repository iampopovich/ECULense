package io.github.iampopovich.eculense.core.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.iampopovich.eculense.core.data.SessionRecorder
import io.github.iampopovich.eculense.core.database.EcuDatabase
import io.github.iampopovich.eculense.core.model.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File

/** What the app keeps on the phone (bytes). Nothing here is deleted automatically (Alex's decision). */
data class StorageSizes(val databaseBytes: Long, val accelBytes: Long, val cacheBytes: Long)

/** State of the one-time «compact database» work. */
sealed interface CompactState {
    data object Idle : CompactState
    data object Running : CompactState
    data class Done(val beforeBytes: Long, val afterBytes: Long) : CompactState
    /** Refused because a recording is running (VACUUM would block its inserts for the whole rebuild). */
    data object Recording : CompactState
    data object Failed : CompactState
}

interface StorageService {
    suspend fun sizes(): StorageSizes
    val compaction: Flow<CompactState>
    /** True while a recording is running: the compact button is disabled. */
    val recording: Flow<Boolean>
    fun compact()
}

/** Sizes of files and folders; the database counts with its -wal/-shm/-journal companions. */
object StorageFiles {
    fun databaseBytes(db: File): Long = listOf("", "-wal", "-shm", "-journal").sumOf { File(db.path + it).takeIf(File::isFile)?.length() ?: 0L }

    fun dirBytes(dir: File): Long = if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

internal class AndroidStorageService(private val context: Context, private val recorder: SessionRecorder?) : StorageService {
    private val work get() = WorkManager.getInstance(context)

    override suspend fun sizes(): StorageSizes = withContext(Dispatchers.IO) {
        StorageSizes(
            StorageFiles.databaseBytes(context.getDatabasePath(EcuDatabase.FILE_NAME)),
            StorageFiles.dirBytes(File(context.filesDir, "accel")),
            StorageFiles.dirBytes(context.cacheDir),
        )
    }

    override val recording: Flow<Boolean> = recorder?.activeSessionId?.map { it != null } ?: flowOf(false)

    override val compaction: Flow<CompactState> = work.getWorkInfosForUniqueWorkFlow(UNIQUE).map { infos ->
        val info = infos.firstOrNull()
        compactStateOf(info?.state, info?.outputData ?: Data.EMPTY)
    }

    override fun compact() {
        work.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<DbVacuumWorker>().build())
    }

    internal companion object {
        const val UNIQUE = "ecu-db-vacuum"
        const val KEY_BEFORE = "before"
        const val KEY_AFTER = "after"
        const val KEY_RECORDING = "recording"

        /** The unique vacuum job's last state → what the Backup screen shows (null = never started). */
        fun compactStateOf(state: WorkInfo.State?, output: Data): CompactState = when (state) {
            null, WorkInfo.State.CANCELLED -> CompactState.Idle
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> CompactState.Running
            WorkInfo.State.SUCCEEDED -> CompactState.Done(output.getLong(KEY_BEFORE, 0), output.getLong(KEY_AFTER, 0))
            WorkInfo.State.FAILED -> if (output.getBoolean(KEY_RECORDING, false)) CompactState.Recording else CompactState.Failed
        }
    }
}

/** Outcome of one compaction attempt (pure part of [DbVacuumWorker], JVM-tested). */
internal sealed interface VacuumOutcome {
    data object Recording : VacuumOutcome
    data class Done(val beforeBytes: Long, val afterBytes: Long) : VacuumOutcome
    data class Failed(val error: Exception) : VacuumOutcome

    fun toResult(): ListenableWorker.Result = when (this) {
        Recording -> ListenableWorker.Result.failure(workDataOf(AndroidStorageService.KEY_RECORDING to true))
        is Done -> ListenableWorker.Result.success(workDataOf(AndroidStorageService.KEY_BEFORE to beforeBytes, AndroidStorageService.KEY_AFTER to afterBytes))
        is Failed -> ListenableWorker.Result.failure()
    }

    companion object {
        /** Refuses while a recording is running (VACUUM holds the write lock for the whole rebuild); sizes around [vacuum]. */
        fun run(recording: Boolean, size: () -> Long, vacuum: () -> Unit): VacuumOutcome {
            if (recording) return Recording
            val before = size()
            return try {
                vacuum()
                Done(before, size())
            } catch (e: Exception) {
                Failed(e)
            }
        }
    }
}

/**
 * R3.2 «Compact database»: VACUUM rebuilds ecu.db without free pages (left by deleted recordings). It needs free space
 * of about the database size and holds the write lock for the whole rebuild, so it never runs while a recording is
 * active; the user starts it, nothing runs on a schedule.
 */
class DbVacuumWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val recording = getKoin().getOrNull<SessionRecorder>()?.activeSessionId?.value != null
        val file = applicationContext.getDatabasePath(EcuDatabase.FILE_NAME)
        val logger = getKoin().getOrNull<AppLogger>() ?: AppLogger.NONE
        val outcome = VacuumOutcome.run(recording, size = { StorageFiles.databaseBytes(file) }) {
            val db = get<EcuDatabase>().openHelper.writableDatabase
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            db.execSQL("VACUUM")
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }
        when (outcome) {
            is VacuumOutcome.Done -> runCatching { android.util.Log.i(TAG, "VACUUM done: ${outcome.beforeBytes} -> ${outcome.afterBytes} bytes") }
            is VacuumOutcome.Failed -> logger.error(TAG, "VACUUM failed: ${outcome.error::class.java.simpleName}", outcome.error)
            VacuumOutcome.Recording -> Unit
        }
        outcome.toResult()
    }

    private companion object {
        const val TAG = "EcuStorage"
    }
}

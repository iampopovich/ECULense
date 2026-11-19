package io.github.iampopovich.eculense.core.datapacks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.net.ConnectivityManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.concurrent.TimeUnit

/** Pack state for the «Data and references» screen. progress: null — not downloading, -1 — downloading, size unknown. */
data class PackState(
    val manifest: PackManifest,
    val installed: PackStore.Installed? = null,
    val progress: Int? = null,
    val updateVersion: String? = null,
    val error: String? = null,
    /** Bytes already downloaded (download in progress or a partial file is present — «Resume»). */
    val downloadedBytes: Long = 0,
    /** Download is waiting for a retry after an interruption (WorkManager retries by itself). */
    val retrying: Boolean = false,
) {
    val downloading: Boolean get() = progress != null
}

/**
 * What screens need from packs: state, download/cancel/delete, network type. An interface so ViewModels
 * can be tested without WorkManager.
 */
interface DataPackService {
    /** Reference data ([PackGroup.REFERENCE]). */
    fun observe(): Flow<List<PackState>>
    fun observe(group: PackGroup): Flow<List<PackState>> = observe()
    fun download(id: String, version: String? = null)
    fun cancel(id: String)
    fun delete(id: String)
    fun isMeteredNetwork(): Boolean
    suspend fun checkUpdates(): Map<String, String>
}

/**
 * On-demand data packs: download via WorkManager (survives leaving the screen), atomic version install,
 * deletion, weekly update check on unmetered networks only. Confirmation on mobile data happens on the screen
 * ([isMeteredNetwork] before calling [download]). [sources] — catalogs that change at runtime (AI models from :core:ailocal).
 */
class DataPacks(
    private val context: Context,
    val store: PackStore,
    val installer: PackInstaller,
    private val base: List<PackManifest> = PackCatalog.all,
    private val sources: List<PackSource> = emptyList(),
) : DataPackService {
    private val changes = MutableStateFlow(0)
    private val work get() = WorkManager.getInstance(context)

    val catalog: List<PackManifest> get() = base + sources.flatMap { it.manifests() }

    fun manifest(id: String): PackManifest? = catalog.firstOrNull { it.id == id }

    fun installed(id: String): PackStore.Installed? = store.installed(id)

    fun file(id: String, name: String): File? = store.file(id, name)

    /** Any pack change (install, delete) — readers reopen their files. */
    val revisions: Flow<Int> get() = changes

    override fun observe(): Flow<List<PackState>> = observe(PackGroup.REFERENCE)

    override fun observe(group: PackGroup): Flow<List<PackState>> =
        combine(changes, work.getWorkInfosByTagFlow(TAG)) { _, infos ->
            catalog.filter { it.group == group }.map { m -> state(m, infos.filter { "$TAG:${m.id}" in it.tags }) }
        }

    fun observe(id: String): Flow<PackState?> =
        combine(changes, work.getWorkInfosByTagFlow(TAG)) { _, infos ->
            manifest(id)?.let { m -> state(m, infos.filter { "$TAG:${m.id}" in it.tags }) }
        }

    private fun state(m: PackManifest, infos: List<WorkInfo>): PackState {
        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        val failed = infos.firstOrNull { it.state == WorkInfo.State.FAILED }
        val installed = store.installed(m.id)
        val done = running?.progress?.getLong(KEY_DONE, -1L)?.takeIf { it >= 0 }
        return PackState(
            manifest = m,
            installed = installed,
            progress = running?.let { it.progress.getInt(KEY_PROGRESS, -1) },
            updateVersion = store.availableUpdate(m.id)?.takeIf { it != installed?.version },
            error = if (running == null) failed?.outputData?.getString(KEY_ERROR) else null,
            downloadedBytes = done ?: if (m.resumable && installed == null) store.partialBytes(m.id) else 0L,
            retrying = running != null && running.state == WorkInfo.State.ENQUEUED && running.runAttemptCount > 0,
        )
    }

    /** version = null — the pinned version (or, for «Update», the one found by the check). */
    override fun download(id: String, version: String?) {
        val resumable = manifest(id)?.resumable == true
        // A user tap replaces a task waiting for retry with a new one (expedited, from the open app); the download
        // resumes from .part, so replacing a running download loses nothing.
        val policy = if (resumable) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        work.enqueueUniqueWork("$TAG:$id", policy, downloadRequest(id, version, resumable))
    }


    override fun cancel(id: String) {
        work.cancelUniqueWork("$TAG:$id")
    }

    override fun delete(id: String) {
        cancel(id)
        store.delete(id)
        work.pruneWork()
        changed()
    }

    /** Notify screens of a change (install, delete, new model in the catalog). */
    fun changed() {
        changes.value++
    }

    override fun isMeteredNetwork(): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    /** Once a week, Wi-Fi/unmetered only; only downloaded packs are checked. */
    fun scheduleUpdateCheck() {
        val request = PeriodicWorkRequestBuilder<PackUpdateWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        work.enqueueUniquePeriodicWork("$TAG:update-check", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Check for updates now (the weekly worker does the same). Returns the new versions found. */
    override suspend fun checkUpdates(): Map<String, String> {
        val found = mutableMapOf<String, String>()
        for (m in catalog) {
            val installed = store.installed(m.id) ?: continue
            val latest = runCatching { installer.latestVersion(m) }.getOrNull() ?: continue
            if (latest != installed.version) found[m.id] = latest
            store.setAvailableUpdate(m.id, latest.takeIf { it != installed.version })
        }
        changed()
        return found
    }

    companion object {
        /**
         * Multi-gigabyte packs (models) use an expedited task: it starts immediately while the user is still in the app
         * (they just tapped «Download») and manages to become a foreground service — Android 12+/16 forbids starting
         * a foreground service from the background (`mAllowStartForeground false`). If there is no expedited quota — a regular
         * task; without a foreground service the system stops it after ~10 minutes and WorkManager resumes where it broke off (Range).
         * Network/DNS failure — Result.retry() with exponential backoff from [BACKOFF_SECONDS].
         */
        internal fun downloadRequest(id: String, version: String?, resumable: Boolean): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<PackDownloadWorker>()
                .setInputData(workDataOf(KEY_ID to id, KEY_VERSION to version))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .apply { if (resumable) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
                .addTag(TAG)
                .addTag("$TAG:$id")
                .build()
        const val TAG = "datapack"
        internal const val BACKOFF_SECONDS = 30L
        internal const val KEY_ID = "id"
        internal const val KEY_VERSION = "version"
        internal const val KEY_PROGRESS = "progress"
        internal const val KEY_DONE = "done"
        internal const val KEY_ERROR = "error"
        internal const val LOG = "EcuDataPacks"
    }
}

/** Downloads and installs one pack; progress in percent goes to WorkInfo.progress. */
class PackDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val packs: DataPacks by inject()

    override suspend fun doWork(): Result {
        val id = inputData.getString(DataPacks.KEY_ID) ?: return Result.failure()
        val manifest = packs.manifest(id) ?: return Result.failure(workDataOf(DataPacks.KEY_ERROR to "unknown pack $id"))
        val started = System.currentTimeMillis()
        var lastPct = -2
        var lastDone = 0L
        return try {
            // Multi-gigabyte file: foreground task (WorkManager gives a regular one 10 minutes) with a progress notification.
            // Allowed while the app is on screen (started by a tap — expedited); Android 12+ won't let a retry from the
            // background into the foreground — then we download as a regular task in chunks until the system stops it, and resume.
            if (manifest.resumable) runCatching { setForeground(foregroundInfo(titleOf(manifest), -1)) }
                .onSuccess { Log.i(DataPacks.LOG, "pack $id: foreground download (attempt $runAttemptCount)") }
                .onFailure { Log.w(DataPacks.LOG, "foreground not allowed, continuing in background: ${it.message}") }
            setProgress(workDataOf(DataPacks.KEY_PROGRESS to -1))
            val installed = packs.installer.install(manifest, inputData.getString(DataPacks.KEY_VERSION)) { done, total ->
                val pct = total?.takeIf { it > 0 }?.let { (done * 100 / it).toInt().coerceIn(0, 100) } ?: -1
                // bytes — for «1.2 of 2.6 GB»; at most once per 4 MB so as not to hammer the WorkManager database
                if (pct != lastPct || done - lastDone >= 4L shl 20) {
                    val pctChanged = pct != lastPct
                    lastPct = pct
                    lastDone = done
                    setProgressAsync(workDataOf(DataPacks.KEY_PROGRESS to pct, DataPacks.KEY_DONE to done))
                    if (manifest.resumable && pctChanged) runCatching { setForegroundAsync(foregroundInfo(titleOf(manifest), pct)) }
                }
            }
            Log.i(DataPacks.LOG, "pack $id ${installed.version} installed: ${installed.sizeBytes} bytes in ${System.currentTimeMillis() - started} ms")
            packs.changed()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(DataPacks.LOG, "pack $id failed after ${System.currentTimeMillis() - started} ms (attempt $runAttemptCount): ${e.message}")
            packs.changed()
            // Network drop on a multi-gigabyte file: retry with resume (WorkManager, exponential backoff).
            val transient = e is java.io.IOException && e !is PackIntegrityException && (e as? HttpStatusException)?.code.let { it == null || it >= 500 || it == 429 }
            if (manifest.resumable && transient && runAttemptCount < MAX_RETRIES) Result.retry()
            else Result.failure(workDataOf(DataPacks.KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    /** Before Android 12 an expedited task runs as a foreground service — WorkManager asks for the notification here. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(inputData.getString(DataPacks.KEY_ID)?.let { packs.manifest(it) }?.let { titleOf(it) } ?: applicationContext.getString(R.string.pack_notif_fallback_title), -1)

    private fun titleOf(m: PackManifest): String = if (m.titleRes != 0) applicationContext.getString(m.titleRes) else m.title

    private fun foregroundInfo(title: String, pct: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.pack_notif_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.pack_notif_title, title))
            .setContentText(if (pct >= 0) "$pct %" else applicationContext.getString(R.string.pack_notif_preparing))
            .setProgress(100, pct.coerceAtLeast(0), pct < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private companion object {
        // Each attempt resumes where it broke off, so we can allow more attempts (the phone's network is flaky).
        const val MAX_RETRIES = 10
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 4201
    }
}

class PackUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val packs: DataPacks by inject()

    override suspend fun doWork(): Result {
        val found = packs.checkUpdates()
        Log.i(DataPacks.LOG, "update check: ${found.keys}")
        return Result.success()
    }
}

package io.github.iampopovich.eculense.core.datapacks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.net.ConnectivityManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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

/** Состояние пакета для экрана «Данные и справочники». progress: null — не качается, -1 — качается, размер неизвестен. */
data class PackState(
    val manifest: PackManifest,
    val installed: PackStore.Installed? = null,
    val progress: Int? = null,
    val updateVersion: String? = null,
    val error: String? = null,
    /** Сколько байт уже скачано (идёт загрузка или лежит недокачанный файл — «Продолжить»). */
    val downloadedBytes: Long = 0,
    /** Загрузка ждёт повтора после обрыва (WorkManager повторит сам). */
    val retrying: Boolean = false,
) {
    val downloading: Boolean get() = progress != null
}

/**
 * Что нужно экранам от пакетов: состояние, скачать/отменить/удалить, тип сети. Интерфейс — чтобы ViewModel-и
 * тестировались без WorkManager.
 */
interface DataPackService {
    /** Справочники ([PackGroup.REFERENCE]). */
    fun observe(): Flow<List<PackState>>
    fun observe(group: PackGroup): Flow<List<PackState>> = observe()
    fun download(id: String, version: String? = null)
    fun cancel(id: String)
    fun delete(id: String)
    fun isMeteredNetwork(): Boolean
    suspend fun checkUpdates(): Map<String, String>
}

/**
 * Пакеты данных по запросу: скачивание через WorkManager (переживает уход с экрана), атомарная установка версии,
 * удаление, еженедельная проверка обновлений только по безлимитной сети. Подтверждение на мобильной сети — на экране
 * ([isMeteredNetwork] до вызова [download]). [sources] — каталоги, меняющиеся на ходу (модели ИИ из :core:ailocal).
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

    /** Любое изменение пакета (установка, удаление) — читатели переоткрывают файлы. */
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

    /** version = null — зафиксированная (или для «Обновить» — найденная проверкой) версия. */
    override fun download(id: String, version: String?) {
        val request = OneTimeWorkRequestBuilder<PackDownloadWorker>()
            .setInputData(workDataOf(KEY_ID to id, KEY_VERSION to version))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .addTag("$TAG:$id")
            .build()
        work.enqueueUniqueWork("$TAG:$id", ExistingWorkPolicy.KEEP, request)
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

    /** Сообщить экранам об изменении (установка, удаление, новая модель в каталоге). */
    fun changed() {
        changes.value++
    }

    override fun isMeteredNetwork(): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    /** Раз в неделю, только Wi-Fi/безлимит; проверяются только скачанные пакеты. */
    fun scheduleUpdateCheck() {
        val request = PeriodicWorkRequestBuilder<PackUpdateWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        work.enqueueUniquePeriodicWork("$TAG:update-check", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Проверка обновлений сейчас (её же делает еженедельный воркер). Возвращает найденные новые версии. */
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
        const val TAG = "datapack"
        internal const val KEY_ID = "id"
        internal const val KEY_VERSION = "version"
        internal const val KEY_PROGRESS = "progress"
        internal const val KEY_DONE = "done"
        internal const val KEY_ERROR = "error"
        internal const val LOG = "EcuDataPacks"
    }
}

/** Скачивает и устанавливает один пакет; прогресс в процентах — в WorkInfo.progress. */
class PackDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val packs: DataPacks by inject()

    override suspend fun doWork(): Result {
        val id = inputData.getString(DataPacks.KEY_ID) ?: return Result.failure()
        val manifest = packs.manifest(id) ?: return Result.failure(workDataOf(DataPacks.KEY_ERROR to "неизвестный пакет $id"))
        val started = System.currentTimeMillis()
        var lastPct = -2
        var lastDone = 0L
        return try {
            // Многогигабайтный файл: foreground-задача (обычной WorkManager даёт 10 минут) с уведомлением о прогрессе.
            if (manifest.resumable) runCatching { setForeground(foregroundInfo(manifest.title, -1)) }
                .onFailure { Log.w(DataPacks.LOG, "foreground not allowed: ${it.message}") }
            setProgress(workDataOf(DataPacks.KEY_PROGRESS to -1))
            val installed = packs.installer.install(manifest, inputData.getString(DataPacks.KEY_VERSION)) { done, total ->
                val pct = total?.takeIf { it > 0 }?.let { (done * 100 / it).toInt().coerceIn(0, 100) } ?: -1
                // байты — для «1,2 из 2,6 ГБ»; не чаще чем раз в 4 МБ, чтобы не дёргать базу WorkManager
                if (pct != lastPct || done - lastDone >= 4L shl 20) {
                    val pctChanged = pct != lastPct
                    lastPct = pct
                    lastDone = done
                    setProgressAsync(workDataOf(DataPacks.KEY_PROGRESS to pct, DataPacks.KEY_DONE to done))
                    if (manifest.resumable && pctChanged) runCatching { setForegroundAsync(foregroundInfo(manifest.title, pct)) }
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
            // Обрыв сети на многогигабайтном файле: повтор с докачкой (WorkManager, экспоненциальная пауза).
            val transient = e is java.io.IOException && e !is PackIntegrityException && (e as? HttpStatusException)?.code.let { it == null || it >= 500 || it == 429 }
            if (manifest.resumable && transient && runAttemptCount < MAX_RETRIES) Result.retry()
            else Result.failure(workDataOf(DataPacks.KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    private fun foregroundInfo(title: String, pct: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Загрузки", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Загрузка: $title")
            .setContentText(if (pct >= 0) "$pct %" else "подготовка…")
            .setProgress(100, pct.coerceAtLeast(0), pct < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val MAX_RETRIES = 6
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

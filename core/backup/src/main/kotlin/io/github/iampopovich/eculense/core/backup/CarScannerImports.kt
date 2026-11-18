package io.github.iampopovich.eculense.core.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.iampopovich.eculense.core.data.ImportHistoryItem
import io.github.iampopovich.eculense.core.data.ImportProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Состояние фонового импорта для экрана. */
sealed interface ImportWorkState {
    data object Idle : ImportWorkState
    /** total = 0 — ещё копируется файл / читается архив. */
    data class Running(val done: Int, val total: Int) : ImportWorkState
    data class Finished(val ok: Boolean, val error: String? = null) : ImportWorkState
}

/** Что нужно экрану от фонового импорта — интерфейс, чтобы ViewModel тестировалась без WorkManager. */
interface ImportService {
    val state: Flow<ImportWorkState>
    /** releaseUri — отдать постоянный доступ SAF к файлу после импорта (одиночный выбор файла, не папка). */
    fun start(uri: String, fileName: String, releaseUri: Boolean)
    fun cancel()
}

/**
 * Импорт бэкапа Car Scanner в WorkManager (1.27 п.2): переживает уход с экрана и сворачивание. Запуск — expedited
 * прямо из нажатия, пока приложение на экране: так воркер успевает стать foreground-службой (Android 12+/16 не
 * разрешает её из фона, см. DataPacks). Нет квоты expedited — обычная задача (импорт короче 10 минут).
 */
class CarScannerImports(private val context: Context) : ImportService {
    private val work get() = WorkManager.getInstance(context)

    override val state: Flow<ImportWorkState> = work.getWorkInfosForUniqueWorkFlow(UNIQUE).map { infos ->
        val info = infos.firstOrNull() ?: return@map ImportWorkState.Idle
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ImportWorkState.Running(0, 0)
            WorkInfo.State.RUNNING -> ImportWorkState.Running(info.progress.getInt(KEY_DONE, 0), info.progress.getInt(KEY_TOTAL, 0))
            WorkInfo.State.SUCCEEDED -> ImportWorkState.Finished(ok = true)
            WorkInfo.State.FAILED -> ImportWorkState.Finished(ok = false, error = info.outputData.getString(KEY_ERROR))
            WorkInfo.State.CANCELLED -> ImportWorkState.Finished(ok = false, error = "Импорт отменён")
        }
    }

    override fun start(uri: String, fileName: String, releaseUri: Boolean) {
        work.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, request(uri, fileName, releaseUri))
    }

    override fun cancel() {
        work.cancelUniqueWork(UNIQUE)
    }

    companion object {
        const val UNIQUE = "carscanner-import"
        internal const val KEY_URI = "uri"
        internal const val KEY_NAME = "name"
        internal const val KEY_RELEASE = "release"
        internal const val KEY_DONE = "done"
        internal const val KEY_TOTAL = "total"
        internal const val KEY_ERROR = "error"
        internal const val KEY_ADDED = "recordingsAdded"
        internal const val TAG = "EcuImport"

        internal fun request(uri: String, fileName: String, releaseUri: Boolean): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<CarScannerImportWorker>()
                .setInputData(workDataOf(KEY_URI to uri, KEY_NAME to fileName, KEY_RELEASE to releaseUri))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(UNIQUE)
                .build()
    }
}

/** Импорт одного .cbz/.brc: foreground-уведомление «Записи 23 из 73» с «Отменить», итог — уведомление и строка в истории. */
class CarScannerImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val runner: ImportRunner by inject()

    override suspend fun doWork(): Result {
        val uri = inputData.getString(CarScannerImports.KEY_URI) ?: return Result.failure()
        val name = inputData.getString(CarScannerImports.KEY_NAME) ?: "backup.cbz"
        val started = System.currentTimeMillis()
        runCatching { setForeground(foregroundInfo(ImportProgress(0, 0))) }
            .onFailure { Log.w(CarScannerImports.TAG, "foreground not allowed, importing in background: ${it.javaClass.simpleName}") }
        var shown = -1
        return try {
            val item = runner.run(uri, name) { p ->
                setProgressAsync(workDataOf(CarScannerImports.KEY_DONE to p.done, CarScannerImports.KEY_TOTAL to p.total))
                if (p.done != shown) {
                    shown = p.done
                    runCatching { setForegroundAsync(foregroundInfo(p)) }
                }
            }
            // только счётчики: без имён файлов и путей пользователя
            Log.i(
                CarScannerImports.TAG,
                "import finished in ${System.currentTimeMillis() - started} ms: recordings +${item.recordingsAdded} skipped=${item.recordingsSkipped} " +
                    "trips +${item.tripsAdded} updated=${item.tripsUpdated} same=${item.tripsSkipped} dtc +${item.dtcAdded} " +
                    "carFields=${item.carFieldsUpdated} units=${item.unitsUpdated} errors=${item.errors}",
            )
            ImportNotifications.finished(applicationContext, item)
            Result.success(workDataOf(CarScannerImports.KEY_ADDED to item.recordingsAdded))
        } catch (e: CancellationException) {
            Log.i(CarScannerImports.TAG, "import cancelled after ${System.currentTimeMillis() - started} ms")
            throw e
        } catch (e: Exception) {
            Log.w(CarScannerImports.TAG, "import failed after ${System.currentTimeMillis() - started} ms: ${e.javaClass.simpleName}")
            ImportNotifications.failed(applicationContext, e.message ?: e.javaClass.simpleName)
            Result.failure(workDataOf(CarScannerImports.KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        } finally {
            if (inputData.getBoolean(CarScannerImports.KEY_RELEASE, false)) {
                runCatching { applicationContext.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(ImportProgress(0, 0))

    private fun foregroundInfo(p: ImportProgress): ForegroundInfo {
        val n = ImportNotifications.progress(applicationContext, p, WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(ImportNotifications.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(ImportNotifications.ID_PROGRESS, n)
    }
}

internal object ImportNotifications {
    const val CHANNEL = "import"
    const val ID_PROGRESS = 4301
    const val ID_RESULT = 4302

    private fun channel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Импорт данных", NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun progress(context: Context, p: ImportProgress, cancel: PendingIntent): android.app.Notification {
        channel(context)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Импорт из Car Scanner")
            .setContentText(if (p.total > 0) "Записи ${p.done} из ${p.total}" else "Подготовка бэкапа…")
            .setProgress(p.total.coerceAtLeast(1), p.done, p.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launch(context))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отменить", cancel)
            .build()
    }

    fun finished(context: Context, item: ImportHistoryItem) =
        post(context, "Импорт завершён", summary(item))

    fun failed(context: Context, message: String) = post(context, "Импорт не удался", message)

    fun summary(item: ImportHistoryItem): String = buildList {
        add("записи +${item.recordingsAdded}")
        add("поездки +${item.tripsAdded}" + if (item.tripsUpdated > 0) " (обновлено ${item.tripsUpdated})" else "")
        if (item.dtcAdded > 0) add("события DTC +${item.dtcAdded}")
        if (item.errors > 0) add("ошибок ${item.errors}")
    }.joinToString(" · ")

    private fun post(context: Context, title: String, text: String) {
        channel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(launch(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_RESULT, n)
    }

    private fun launch(context: Context): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, ID_RESULT, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
}

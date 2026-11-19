package io.github.iampopovich.eculense.core.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.iampopovich.eculense.core.data.AnalyticsRepository
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.DataEvents
import io.github.iampopovich.eculense.core.data.MaintenanceRepository
import io.github.iampopovich.eculense.core.data.RecordingTrips
import org.koin.android.ext.koin.androidContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/** Analysis after recording/import; mode = backfill — only trips from old recordings (app startup). */
class AnalysisWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    override suspend fun doWork(): Result = runCatching {
        if (inputData.getString(KEY_MODE) == MODE_BACKFILL) {
            val added = get<TripBackfill>().run()
            Log.i(AndroidNotifier.TAG, "startup backfill trips=$added")
        } else {
            val r = get<PostTripAnalysis>().run()
            Log.i(AndroidNotifier.TAG, "post-trip analysis trips=${r.tripsAdded} notified=${r.notified}")
        }
        Result.success()
    }.getOrElse { e ->
        Log.w(AndroidNotifier.TAG, "analysis failed: ${e.message}")
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }

    companion object {
        const val KEY_MODE = "mode"
        const val MODE_BACKFILL = "backfill"
    }
}

/** Daily maintenance check (offline). */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    override suspend fun doWork(): Result = runCatching {
        val n = get<MaintenanceCheck>().run()
        Log.i(AndroidNotifier.TAG, "maintenance check notified=$n")
        Result.success()
    }.getOrElse { Result.retry() }
}

/** Schedules background work. Implements [DataEvents]: the data layer calls it after recording and import. */
class BackgroundScheduler(private val context: Context) : DataEvents {
    private val work get() = WorkManager.getInstance(context)

    override fun recordingFinished(sessionId: Long) = enqueueAnalysis()

    override fun importFinished() = enqueueAnalysis()

    /** APPEND_OR_REPLACE: a new recording during analysis — one more run after the current one, not in parallel. */
    fun enqueueAnalysis() {
        work.enqueueUniqueWork(ANALYSIS_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<AnalysisWorker>().build())
    }

    fun onAppStart() {
        AndroidNotifier.ensureChannels(context)
        work.enqueueUniqueWork(
            BACKFILL_WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AnalysisWorker>().setInputData(workDataOf(AnalysisWorker.KEY_MODE to AnalysisWorker.MODE_BACKFILL)).build(),
        )
        work.enqueueUniquePeriodicWork(
            MAINTENANCE_WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    companion object {
        const val ANALYSIS_WORK = "post-trip-analysis"
        const val BACKFILL_WORK = "trip-backfill"
        const val MAINTENANCE_WORK = "maintenance-daily"
    }
}

/** Add/remove = a line in AppModules + settings.gradle.kts; without the module the data layer recomputes findings. */
val backgroundModule = module {
    single<Notifier> { AndroidNotifier(androidContext()) }
    single<AlertLedger>(named("findings-ledger")) { PrefsAlertLedger(androidContext(), "findings") }
    single<AlertLedger>(named("maintenance-ledger")) { PrefsAlertLedger(androidContext(), "maintenance") }
    single<TripBackfill> {
        val trips = get<RecordingTrips>()
        TripBackfill { trips.backfill() }
    }
    single {
        val cars = get<CarRepository>()
        val analytics = get<AnalyticsRepository>()
        val trips = get<TripBackfill>()
        PostTripAnalysis(
            backfill = { trips.run() },
            cars = { cars.cars().map { it.id to it.name } },
            findings = { id -> analytics.findings(id).map { it.finding } },
            recompute = { id -> analytics.recompute(id) },
            ledger = get(named("findings-ledger")),
            notifier = get(),
        )
    }
    single {
        val cars = get<CarRepository>()
        val maintenance = get<MaintenanceRepository>()
        MaintenanceCheck(
            cars = { cars.cars().map { it.id to it.name } },
            due = { id -> maintenance.due(id) },
            ledger = get(named("maintenance-ledger")),
            notifier = get(),
        )
    }
    single(createdAtStart = true) { BackgroundScheduler(androidContext()).also { it.onAppStart() } } bind DataEvents::class
}

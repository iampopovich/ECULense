package io.github.iampopovich.eculense.core.background

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.FindingArg
import io.github.iampopovich.eculense.core.analytics.FindingKey
import io.github.iampopovich.eculense.core.analytics.FindingText
import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import io.github.iampopovich.eculense.core.analytics.MaintenanceStatus
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import io.github.iampopovich.eculense.core.model.MaintenanceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/** Workers via work-testing: decisions are in fakes, notifications are real (in the test package). */
@RunWith(AndroidJUnit4::class)
class WorkersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifier = AndroidNotifier(context)
    private var recomputed = 0
    private var backfilled = 0

    private class Ledger : AlertLedger {
        var keys = emptySet<String>()
        override suspend fun load() = keys
        override suspend fun save(keys: Set<String>) { this.keys = keys }
    }

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        nm().cancelAll()
        val analysis = PostTripAnalysis(
            backfill = { backfilled++; 1 },
            cars = { listOf(1L to "Test car") },
            findings = { emptyList() },
            recompute = { recomputed++; listOf(Finding("volt", Severity.CRITICAL, FindingText(FindingKey.TITLE_VOLTAGE_LOW), FindingText(FindingKey.VOLTAGE_HIGH_OK, FindingArg.Num(14.8)))) },
            ledger = Ledger(), notifier = notifier,
        )
        val check = MaintenanceCheck(
            cars = { listOf(1L to "Test car") },
            due = { listOf(MaintenanceDue(MaintenanceItem(id = 1, carId = 1, title = "", kind = MaintenanceKind.OIL_AND_FILTER, lastDoneAtMs = 1), MaintenanceStatus.OVERDUE, kmLeft = -300.0)) },
            ledger = Ledger(), notifier = notifier,
        )
        startKoin {
            modules(
                module {
                    single { analysis }
                    single { check }
                    single<TripBackfill> { TripBackfill { backfilled++; 2 } }
                },
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
        nm().cancelAll()
    }

    private fun nm() = context.getSystemService(NotificationManager::class.java)

    @Test
    fun analysisWorkerRecomputesAndPostsToAnalysisChannel() = runBlocking {
        val result = TestListenableWorkerBuilder<AnalysisWorker>(context).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, recomputed)
        val posted = nm().activeNotifications.single { it.id == AndroidNotifier.ID_ANALYSIS }
        assertEquals(AndroidNotifier.CHANNEL_ANALYSIS, posted.notification.channelId)
        assertTrue(posted.notification.extras.getString("android.title")!!.contains(context.getString(io.github.iampopovich.eculense.core.analyticstext.R.string.finding_title_voltage_low)))
        // contentIntent is empty in the library's test APK: it has no launcher activity (in the app it's MainActivity)
    }

    @Test
    fun backfillModeOnlyBuildsTrips() = runBlocking {
        val worker = TestListenableWorkerBuilder<AnalysisWorker>(context)
            .setInputData(workDataOf(AnalysisWorker.KEY_MODE to AnalysisWorker.MODE_BACKFILL)).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(0, recomputed)
        assertEquals(1, backfilled)
        assertTrue(nm().activeNotifications.none { it.id == AndroidNotifier.ID_ANALYSIS })
    }

    @Test
    fun maintenanceWorkerPostsToMaintenanceChannelOnce() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), TestListenableWorkerBuilder<MaintenanceWorker>(context).build().doWork())
        val posted = nm().activeNotifications.single { it.id == AndroidNotifier.ID_MAINTENANCE }
        assertEquals(AndroidNotifier.CHANNEL_MAINTENANCE, posted.notification.channelId)
        assertEquals(
            context.getString(R.string.bg_maintenance_one, context.getString(io.github.iampopovich.eculense.core.analyticstext.R.string.maint_kind_oil_and_filter)),
            posted.notification.extras.getString("android.title"),
        )
        nm().cancelAll()
        TestListenableWorkerBuilder<MaintenanceWorker>(context).build().doWork()
        assertTrue(nm().activeNotifications.none { it.id == AndroidNotifier.ID_MAINTENANCE })
    }
}

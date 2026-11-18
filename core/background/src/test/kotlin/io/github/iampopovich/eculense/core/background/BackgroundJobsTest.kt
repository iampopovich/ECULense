package io.github.iampopovich.eculense.core.background

import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import io.github.iampopovich.eculense.core.analytics.MaintenanceStatus
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class MemLedger : AlertLedger {
    var keys = emptySet<String>()
    override suspend fun load() = keys
    override suspend fun save(keys: Set<String>) { this.keys = keys }
}

internal class RecordingNotifier : Notifier {
    val findings = mutableListOf<Pair<String, List<Finding>>>()
    val maintenance = mutableListOf<Pair<String, List<MaintenanceDue>>>()
    override fun findings(carName: String, items: List<Finding>) { findings += carName to items }
    override fun maintenance(carName: String, items: List<MaintenanceDue>) { maintenance += carName to items }
}

class BackgroundJobsTest {
    private val ledger = MemLedger()
    private val notifier = RecordingNotifier()

    @Test
    fun `post trip analysis builds trips, recomputes and announces only new problems`() = runTest {
        var stored = listOf(Finding("ltft", Severity.WARNING, "Коррекции", "m"))
        var next = stored + Finding("volt", Severity.CRITICAL, "Напряжение", "m")
        var backfills = 0
        val job = PostTripAnalysis(
            backfill = { backfills++; 1 },
            cars = { listOf(1L to "Тестовая") },
            findings = { stored },
            recompute = { stored = next; next },
            ledger = ledger, notifier = notifier,
        )

        val r = job.run()
        assertEquals(1, backfills)
        assertEquals(1, r.tripsAdded)
        assertEquals(listOf("volt"), notifier.findings.single().second.map { it.ruleId })
        assertEquals("Тестовая", notifier.findings.single().first)

        // второй прогон с теми же находками — тишина
        job.run()
        assertEquals(1, notifier.findings.size)
    }

    @Test
    fun `nothing new means no notification at all`() = runTest {
        val same = listOf(Finding("ltft", Severity.WARNING, "Коррекции", "m"))
        PostTripAnalysis({ 0 }, { listOf(1L to "Тестовая") }, { same }, { same }, ledger, notifier).run()
        assertTrue(notifier.findings.isEmpty())
    }

    @Test
    fun `daily maintenance check reminds once`() = runTest {
        val due = listOf(MaintenanceDue(MaintenanceItem(id = 3, carId = 1, title = "Масло", lastDoneAtMs = 1), MaintenanceStatus.OVERDUE))
        val job = MaintenanceCheck(cars = { listOf(1L to "Тестовая") }, due = { due }, ledger = ledger, notifier = notifier)
        assertEquals(1, job.run())
        assertEquals(0, job.run())
        assertEquals(listOf("Масло"), notifier.maintenance.single().second.map { it.item.title })
    }
}

package io.github.iampopovich.eculense.core.backup

import io.github.iampopovich.eculense.core.data.ImportProgress
import io.github.iampopovich.eculense.core.data.ImportReport
import io.github.iampopovich.eculense.core.data.ImportStatus
import io.github.iampopovich.eculense.core.data.InMemoryImportLedger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportRunnerTest {
    private val history = InMemoryImportLedger()

    @Test
    fun `successful import becomes a history row with the report`() = runTest {
        val runner = ImportRunner({ _, _, p -> p(ImportProgress(3, 3)); ImportReport(2, 1, emptyList(), tripsImported = 4, tripsUpdated = 1, dtcEventsAdded = 2) }, history, clock = { 1_000 })
        val item = runner.run("content://x", "Тестовая.cbz")
        assertEquals(ImportStatus.DONE, item.status)
        assertEquals(listOf(2, 1, 4, 1, 2), history.history.value.single().let { listOf(it.recordingsAdded, it.recordingsSkipped, it.tripsAdded, it.tripsUpdated, it.dtcAdded) })
    }

    @Test
    fun `failure and cancellation are recorded too`() = runTest {
        val failing = ImportRunner({ _, _, _ -> error("битый архив") }, history)
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { failing.run("u", "a.cbz") } }
        assertEquals(ImportStatus.FAILED, history.history.value.first().status)
        assertEquals("битый архив", history.history.value.first().message)

        val started = CompletableDeferred<Unit>()
        val slow = ImportRunner({ _, _, p -> p(ImportProgress(1, 5)); started.complete(Unit); awaitCancellation() }, history)
        val job = async { slow.run("u", "b.cbz") }
        started.await()
        job.cancel()
        runCatching { job.await() }
        val cancelled = history.history.value.first()
        assertEquals(ImportStatus.CANCELLED, cancelled.status)
        assertEquals("Отменено: обработано записей 1 из 5", cancelled.message)
    }
}

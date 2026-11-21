package io.github.iampopovich.eculense.core.backup

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.workDataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** R3.2 «Compact database»: the refusal while recording and the job state the Backup screen shows. */
class CompactionTest {
    @Test
    fun `VACUUM is refused while a recording runs and never touches the database`() {
        var vacuumed = false
        var measured = 0
        val outcome = VacuumOutcome.run(recording = true, size = { measured++; 1L }) { vacuumed = true }
        assertEquals(VacuumOutcome.Recording, outcome)
        assertTrue(!vacuumed && measured == 0)
        val result = outcome.toResult() as ListenableWorker.Result.Failure
        assertTrue(result.outputData.getBoolean(AndroidStorageService.KEY_RECORDING, false))
        // ... and the screen shows «refused: recording», not a generic failure
        assertEquals(CompactState.Recording, AndroidStorageService.compactStateOf(WorkInfo.State.FAILED, result.outputData))
    }

    @Test
    fun `a finished VACUUM reports the size before and after`() {
        val sizes = ArrayDeque(listOf(10_000L, 6_000L))
        val outcome = VacuumOutcome.run(recording = false, size = { sizes.removeFirst() }) { }
        assertEquals(VacuumOutcome.Done(10_000L, 6_000L), outcome)
        val result = outcome.toResult() as ListenableWorker.Result.Success
        assertEquals(CompactState.Done(10_000L, 6_000L), AndroidStorageService.compactStateOf(WorkInfo.State.SUCCEEDED, result.outputData))
    }

    @Test
    fun `a failing VACUUM (e g no free space) is a plain failure`() {
        val outcome = VacuumOutcome.run(recording = false, size = { 1L }) { throw IOException("disk full") }
        assertTrue(outcome is VacuumOutcome.Failed)
        val result = outcome.toResult() as ListenableWorker.Result.Failure
        assertEquals(CompactState.Failed, AndroidStorageService.compactStateOf(WorkInfo.State.FAILED, result.outputData))
    }

    @Test
    fun `job states map to idle, running and done`() {
        assertEquals(CompactState.Idle, AndroidStorageService.compactStateOf(null, Data.EMPTY))
        assertEquals(CompactState.Idle, AndroidStorageService.compactStateOf(WorkInfo.State.CANCELLED, Data.EMPTY))
        for (s in listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)) {
            assertEquals(CompactState.Running, AndroidStorageService.compactStateOf(s, Data.EMPTY))
        }
        assertEquals(CompactState.Done(0, 0), AndroidStorageService.compactStateOf(WorkInfo.State.SUCCEEDED, workDataOf()))
    }
}

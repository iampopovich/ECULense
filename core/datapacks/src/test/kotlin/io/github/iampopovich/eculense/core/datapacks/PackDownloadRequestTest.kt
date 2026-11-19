package io.github.iampopovich.eculense.core.datapacks

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Download request: models start expedited (from the open app → a foreground service is allowed). */
class PackDownloadRequestTest {
    @Test
    fun `resumable packs are expedited with a non-expedited fallback, network required, exponential backoff`() {
        val r = DataPacks.downloadRequest("model-x", null, resumable = true)
        assertTrue(r.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, r.workSpec.outOfQuotaPolicy)
        assertEquals(NetworkType.CONNECTED, r.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, r.workSpec.backoffPolicy)
        assertEquals(DataPacks.BACKOFF_SECONDS * 1000, r.workSpec.backoffDelayDuration)
        assertTrue("datapack:model-x" in r.tags)
        assertEquals("model-x", r.workSpec.input.getString("id"))
    }

    @Test
    fun `small reference packs stay regular work`() {
        assertFalse(DataPacks.downloadRequest("vehicles", "v1", resumable = false).workSpec.expedited)
    }
}

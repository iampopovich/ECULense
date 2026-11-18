package io.github.iampopovich.eculense.core.backup

import androidx.work.OutOfQuotaPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Импорт стартует expedited из нажатия (пока приложение видно → foreground-служба разрешена, Android 16). */
class ImportRequestTest {
    @Test
    fun `import is expedited with a regular-work fallback`() {
        val r = CarScannerImports.request("content://tree/doc", "b.cbz", releaseUri = true)
        assertTrue(r.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, r.workSpec.outOfQuotaPolicy)
        assertEquals("content://tree/doc", r.workSpec.input.getString("uri"))
        assertTrue(r.workSpec.input.getBoolean("release", false))
        assertTrue(CarScannerImports.UNIQUE in r.tags)
    }
}

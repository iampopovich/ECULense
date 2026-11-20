package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.obd.PollTier
import io.github.iampopovich.eculense.core.obd.PollTiers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch-7 follow-up: recordings must feed the engine maps (load + trims vs rpm), whatever the dashboard shows. */
class RecorderEngineMapPidsTest {
    @Test
    fun `recording polls engine load and bank 1 trims`() {
        val codes = requiredPids(setOf(0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D)).map { it.code }
        assertTrue(0x04 in codes)
        assertTrue(0x06 in codes)
        assertTrue(0x07 in codes)
        assertFalse(0x08 in codes)
    }

    @Test
    fun `bank 2 trims are polled when supported`() {
        val codes = requiredPids(setOf(0x04, 0x06, 0x07, 0x08, 0x09, 0x0C)).map { it.code }
        assertTrue(codes.containsAll(listOf(0x04, 0x06, 0x07, 0x08, 0x09)))
    }

    @Test
    fun `load and trims are read at least at the slow tier`() {
        listOf(0x04, 0x06, 0x07, 0x08, 0x09).forEach { code ->
            assertTrue(PollTiers.tierOf(code).everyCycles <= PollTier.SLOW.everyCycles)
        }
    }
}

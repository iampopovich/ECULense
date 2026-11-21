package io.github.iampopovich.eculense.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class AppLoggerTest {
    @Test
    fun `attempt logs a failure as a warning and returns it`() {
        val logger = RecordingLogger()
        val result = logger.attempt("T", "export") { error("disk full") }
        assertTrue(result.isFailure)
        val entry = logger.entries.single()
        assertEquals('W', entry.level)
        assertEquals("export failed: IllegalStateException", entry.message)
    }

    @Test
    fun `attempt passes a success through silently`() {
        val logger = RecordingLogger()
        assertEquals(3, logger.attempt("T", "x") { 3 }.getOrNull())
        assertTrue(logger.entries.isEmpty())
    }

    @Test(expected = CancellationException::class)
    fun `attempt rethrows cancellation`() {
        AppLogger.NONE.attempt("T", "x") { throw CancellationException("stop") }
    }
}

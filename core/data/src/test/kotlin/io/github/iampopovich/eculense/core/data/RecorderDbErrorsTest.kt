package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.model.RecordingLogger
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.Session
import io.github.iampopovich.eculense.core.model.SessionSource
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R1.2: a DB error while recording still closes the session, resets the recorder and is logged. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderDbErrorsTest {
    private class ThrowingRepository(
        var failAppend: Boolean = false,
        var failFinishOnce: Boolean = false,
        var failStart: Boolean = false,
    ) : SessionRepository {
        val appended = mutableListOf<SensorReading>()
        val finished = mutableListOf<Long>()
        var finishCalls = 0
        override fun observeSessions() = flowOf(emptyList<Session>())
        override suspend fun start(name: String, source: SessionSource, vin: String?, startedAtMs: Long?, externalId: String?, carId: Long?): Long {
            if (failStart) throw IllegalStateException("disk I/O error")
            return 7L
        }
        override suspend fun assignCar(externalId: String, carId: Long) = Unit
        override suspend fun append(sessionId: Long, readings: List<SensorReading>) {
            if (failAppend) throw IllegalStateException("database or disk is full")
            appended += readings
        }
        override suspend fun finish(sessionId: Long, endedAtMs: Long?) {
            finishCalls++
            if (failFinishOnce && finishCalls == 1) throw IllegalStateException("database is locked")
            finished += sessionId
        }
        override suspend fun readings(sessionId: Long) = appended.toList()
        override suspend fun delete(sessionId: Long) = Unit
        override suspend fun hasExternal(externalId: String) = false
    }

    private fun reading(i: Int) = SensorReading("01.0C", i.toDouble(), "rpm", i.toLong())

    @Test
    fun `append failure ends the recording, finishes the session and logs`() = runTest {
        val controller = FakeConnectionController()
        val repository = ThrowingRepository(failAppend = true)
        val logger = RecordingLogger()
        val handed = mutableListOf<Long>()
        val recorder = LiveSessionRecorder(controller, repository, backgroundScope, onFinished = { handed += it }, logger = logger)

        recorder.start("test")
        runCurrent()
        assertEquals(7L, recorder.activeSessionId.value)
        repeat(30) { controller.readingsFlow.emit(reading(it)) }
        runCurrent()

        assertEquals(listOf(7L), repository.finished)
        assertNull(recorder.activeSessionId.value)
        assertFalse(recorder.activeIsAuto.value)
        assertEquals(listOf(7L), handed)
        assertTrue(logger.entries.toString(), logger.entries.any { it.message.contains("append") })
        // A new recording can start after the failure.
        repository.failAppend = false
        recorder.start("test 2")
        runCurrent()
        assertEquals(7L, recorder.activeSessionId.value)
        recorder.stop()
        runCurrent()
        assertNull(recorder.activeSessionId.value)
    }

    @Test
    fun `final append failure in finally still finishes the session`() = runTest {
        val controller = FakeConnectionController()
        val repository = ThrowingRepository()
        val logger = RecordingLogger()
        val recorder = LiveSessionRecorder(controller, repository, backgroundScope, logger = logger)

        recorder.start("test", carId = null, auto = true)
        runCurrent()
        repeat(3) { controller.readingsFlow.emit(reading(it)) }
        runCurrent()
        repository.failAppend = true
        recorder.stop()
        runCurrent()

        assertEquals(listOf(7L), repository.finished)
        assertNull(recorder.activeSessionId.value)
        assertFalse(recorder.activeIsAuto.value)
        assertTrue(logger.entries.isNotEmpty())
    }

    @Test
    fun `finish failure is retried once and the recorder is reset`() = runTest {
        val controller = FakeConnectionController()
        val repository = ThrowingRepository(failFinishOnce = true)
        val logger = RecordingLogger()
        val recorder = LiveSessionRecorder(controller, repository, backgroundScope, logger = logger)

        recorder.start("test")
        runCurrent()
        recorder.stop()
        runCurrent()

        assertEquals(2, repository.finishCalls)
        assertEquals(listOf(7L), repository.finished)
        assertNull(recorder.activeSessionId.value)
        assertTrue(logger.entries.any { it.message.contains("finish") })
    }

    @Test
    fun `onFinished failure is logged`() = runTest {
        val controller = FakeConnectionController()
        val repository = ThrowingRepository()
        val logger = RecordingLogger()
        val recorder = LiveSessionRecorder(
            controller, repository, backgroundScope,
            onFinished = { throw IllegalStateException("trip build failed") }, logger = logger,
        )

        recorder.start("test")
        runCurrent()
        recorder.stop()
        runCurrent()

        assertNull(recorder.activeSessionId.value)
        assertTrue(logger.entries.toString(), logger.entries.any { it.error?.message == "trip build failed" })
    }

    @Test
    fun `start failure leaves the recorder idle and logs`() = runTest {
        val controller = FakeConnectionController()
        val repository = ThrowingRepository(failStart = true)
        val logger = RecordingLogger()
        val recorder = LiveSessionRecorder(controller, repository, backgroundScope, logger = logger)

        recorder.start("test")
        runCurrent()

        assertNull(recorder.activeSessionId.value)
        assertTrue(logger.entries.isNotEmpty())
        repository.failStart = false
        recorder.start("test 2")
        runCurrent()
        assertEquals(7L, recorder.activeSessionId.value)
        recorder.stop()
        runCurrent()
    }
}

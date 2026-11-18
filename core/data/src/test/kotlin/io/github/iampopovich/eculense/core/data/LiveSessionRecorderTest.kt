package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.Session
import io.github.iampopovich.eculense.core.model.SessionSource
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FakeSessionRepository : SessionRepository {
    val appended = mutableListOf<SensorReading>()
    val finished = mutableListOf<Long>()
    override fun observeSessions() = flowOf(emptyList<Session>())
    override suspend fun start(name: String, source: SessionSource, vin: String?, startedAtMs: Long?, externalId: String?) = 1L
    override suspend fun append(sessionId: Long, readings: List<SensorReading>) {
        appended += readings
    }
    override suspend fun finish(sessionId: Long, endedAtMs: Long?) {
        finished += sessionId
    }
    override suspend fun readings(sessionId: Long) = appended.toList()
    override suspend fun delete(sessionId: Long) = Unit
    override suspend fun hasExternal(externalId: String) = false
}

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionRecorderTest {
    @Test
    fun `records all readings in batches and finishes on stop`() = runTest {
        val controller = FakeConnectionController()
        val repository = FakeSessionRepository()
        val recorder = LiveSessionRecorder(controller, repository, backgroundScope)

        recorder.start("тест")
        runCurrent()
        assertEquals(1L, recorder.activeSessionId.value)
        repeat(30) { controller.readingsFlow.emit(SensorReading("01.0C", it.toDouble(), "rpm", it.toLong())) }
        runCurrent()
        recorder.stop()
        runCurrent() // advanceUntilIdle() не исполняет задачи backgroundScope

        assertEquals(30, repository.appended.size)
        assertEquals(listOf(1L), repository.finished)
        assertNull(recorder.activeSessionId.value)
    }
}

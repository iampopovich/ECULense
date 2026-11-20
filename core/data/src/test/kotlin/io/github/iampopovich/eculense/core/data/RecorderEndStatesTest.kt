package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import io.github.iampopovich.eculense.core.obd.ObdError
import io.github.iampopovich.eculense.core.obd.StandardPids
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch-2 review fix: a recording is closed when the reconnect gives up and on a user disconnect. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderEndStatesTest {
    private val connected = FakeConnectionController.connected(setOf(0x05, 0x06, 0x0C, 0x0D, 0x11, 0x42))

    @Test
    fun `reconnecting keeps the recording open, giving up closes it with the buffered readings`() = runTest {
        val controller = FakeConnectionController(initial = connected)
        val repository = FakeSessionRepository()
        val recorder = LiveSessionRecorder(controller, repository, backgroundScope)
        recorder.start("Test car")
        runCurrent()
        controller.readingsFlow.emit(SensorReading("01.0C", 800.0, "rpm", 1))
        controller.stateFlow.value = ConnectionState.Reconnecting(connected.endpoint, 1, 1_000, ObdError.AdapterClosed)
        runCurrent()
        assertEquals(1L, recorder.activeSessionId.value)
        assertEquals(emptyList<Long>(), repository.finished)

        controller.stateFlow.value = ConnectionState.Failed(connected.endpoint, ObdError.LinkLost(ObdError.AdapterClosed))
        runCurrent()
        assertEquals(listOf(1L), repository.finished)
        assertEquals(1, repository.appended.size)
        assertNull(recorder.activeSessionId.value)
    }

    @Test
    fun `user disconnect closes the recording`() = runTest {
        val controller = FakeConnectionController(initial = connected)
        val repository = FakeSessionRepository()
        val recorder = LiveSessionRecorder(controller, repository, backgroundScope)
        recorder.start("Test car")
        runCurrent()
        controller.disconnect()
        runCurrent()
        assertEquals(listOf(1L), repository.finished)
        assertNull(recorder.activeSessionId.value)
    }

    @Test
    fun `a recording polls what analytics needs and releases it at the end`() = runTest {
        val controller = FakeConnectionController(initial = connected)
        controller.setPolledPids(listOf(StandardPids.byCode(0x0C)!!))
        val recorder = LiveSessionRecorder(controller, FakeSessionRepository(), backgroundScope)
        recorder.start("Test car")
        runCurrent()
        val polled = controller.polled.value.map { it.code }
        // Dashboard selection first, then coolant, trim, speed and module voltage for the rules; throttle is not needed.
        assertEquals(listOf(0x0C, 0x05, 0x06, 0x0D, 0x42), polled)
        recorder.stop()
        runCurrent()
        assertEquals(listOf(0x0C), controller.polled.value.map { it.code })
        assertTrue(controller.polledBy.value.keys == setOf("default"))
    }
}

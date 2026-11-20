package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.EndpointLabel
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class MemoryLastSeen : LastSeenStore {
    val data = MutableStateFlow<Map<Long, LastSeen>>(emptyMap())
    override fun observe(carId: Long): Flow<LastSeen> = data.map { it[carId] ?: LastSeen() }
    override suspend fun saveVoltage(carId: Long, volts: Double, atMs: Long) {
        data.value = data.value + (carId to (data.value[carId] ?: LastSeen()).copy(voltage = volts, voltageAtMs = atMs))
    }
    override suspend fun saveDtcCheck(carId: Long, count: Int, atMs: Long) {
        data.value = data.value + (carId to (data.value[carId] ?: LastSeen()).copy(dtcCount = count, dtcCheckedAtMs = atMs))
    }
}

/** C9: the widget's «last seen» values come only from real links. */
@OptIn(ExperimentalCoroutinesApi::class)
class LastSeenTest {
    private val info = AdapterInfo("ELM327 v1.5", '6', true, 12.6)
    private val demo = AdapterEndpoint("demo", "virtual", "Demo", EndpointLabel.DEMO)
    private val real = AdapterEndpoint("bt", "00:00:00:00:00:01", "OBDII")

    private fun repo(endpoint: AdapterEndpoint, store: MemoryLastSeen): RoomDtcRepository {
        val controller = FakeConnectionController(initial = ConnectionState.Connected(endpoint, info, emptySet()))
        return RoomDtcRepository(
            FakeDtcDao(), clock = { 5_000L }, activeCarId = { 1L }, liveWrites = LiveWriteGate.of(controller),
            onCheck = { count, at -> store.saveDtcCheck(1L, count, at) },
        )
    }

    @Test
    fun `a check without codes is remembered for a real adapter`() = runTest {
        val store = MemoryLastSeen()
        repo(real, store).recordRead(emptyList(), null, null)
        assertEquals(LastSeen(dtcCount = 0, dtcCheckedAtMs = 5_000L), store.data.value[1L])
        repo(real, store).recordRead(listOf(Dtc("P0133", DtcKind.STORED), Dtc("P0133", DtcKind.PENDING)), null, null)
        assertEquals(1, store.data.value[1L]?.dtcCount)
    }

    @Test
    fun `a demo check is not remembered`() = runTest {
        val store = MemoryLastSeen()
        repo(demo, store).recordRead(listOf(Dtc("P0133", DtcKind.STORED)), null, null)
        assertNull(store.data.value[1L])
    }

    @Test
    fun `voltage from a real link is kept at most once a minute, demo voltage never`() = runTest {
        var now = 0L
        val store = MemoryLastSeen()
        val controller = FakeConnectionController(initial = ConnectionState.Connected(real, info, emptySet()))
        LastSeenRecorder(controller, store, { 1L }, LiveWriteGate.of(controller), backgroundScope, clock = { now }).start()
        runCurrent()
        controller.readingsFlow.emit(SensorReading("elm.voltage", 12.4, "V", 0))
        runCurrent()
        now = 30_000
        controller.readingsFlow.emit(SensorReading("elm.voltage", 14.1, "V", 0))
        runCurrent()
        assertEquals(12.4, store.data.value[1L]?.voltage!!, 0.0)
        now = 61_000
        controller.readingsFlow.emit(SensorReading("01.42", 14.2, "V", 0))
        runCurrent()
        assertEquals(14.2, store.data.value[1L]?.voltage!!, 0.0)

        controller.stateFlow.value = ConnectionState.Connected(demo, info, emptySet())
        now = 200_000
        controller.readingsFlow.emit(SensorReading("elm.voltage", 13.9, "V", 0))
        runCurrent()
        assertEquals(14.2, store.data.value[1L]?.voltage!!, 0.0)
    }
}

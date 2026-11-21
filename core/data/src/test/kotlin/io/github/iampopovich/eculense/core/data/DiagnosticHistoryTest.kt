package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.FreezeFrame
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.obd.ClearedCounters
import io.github.iampopovich.eculense.core.obd.ReadinessDecoder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B3/B4: freeze frames and readiness snapshots in the history. */
class DiagnosticHistoryTest {
    private val dao = FakeDtcDao()
    private var now = 5_000_000L
    private val repository = RoomDtcRepository(dao, clock = { now }, activeCarId = { 3L })
    private val frame = FreezeFrame("P0300", listOf(SensorReading("01.0C", 812.5, "rpm", 0), SensorReading("01.05", 88.0, "°C", 0)))
    private val codes = listOf(Dtc("P0133", DtcKind.STORED), Dtc("P0300", DtcKind.STORED), Dtc("P0171", DtcKind.PENDING))

    @Test
    fun `codec round-trips freeze frames, readiness and counters`() {
        assertEquals(frame, DiagCodec.decodeFreezeFrame(DiagCodec.encode(frame)))
        val r = ReadinessDecoder.status(intArrayOf(0x83, 0x07, 0x65, 0x21))
        assertEquals(r, DiagCodec.decodeReadiness(DiagCodec.encode(r), r.milOn, r.dtcCount))
        val c = ClearedCounters(3, 50, null, 0, 7)
        assertEquals(c, DiagCodec.decodeCounters(DiagCodec.encode(c)))
        assertNull(DiagCodec.decodeFreezeFrame(null))
        assertEquals(listOf("01.0C"), DiagCodec.decodeFreezeFrame("01.0C=800|rpm;broken;x=y|z")!!.readings.map { it.sensorId })
    }

    @Test
    fun `the freeze frame goes on the row of the code that stored it`() = runTest {
        assertTrue(repository.recordRead(codes, null, frame))
        assertEquals(listOf(null, DiagCodec.encode(frame), null), dao.items.map { it.freezeFrame })
    }

    @Test
    fun `without a named code the frame goes to the first stored code`() = runTest {
        repository.recordRead(codes, null, frame.copy(dtcCode = null))
        assertEquals("P0133", dao.items.single { it.freezeFrame != null }.code)
    }

    @Test
    fun `a repeated read adds the frame once when the first read had none`() = runTest {
        repository.recordRead(codes)
        now += 1_000
        assertTrue(repository.recordRead(codes, null, frame))
        now += 1_000
        assertTrue(!repository.recordRead(codes, null, frame))
        assertEquals(6, dao.items.size)
    }

    @Test
    fun `readiness snapshots keep monitors and counters`() = runTest {
        val r = ReadinessDecoder.status(intArrayOf(0x82, 0x07, 0x65, 0x00))
        repository.saveReadiness(ReadinessReason.BEFORE_CLEAR, r, ClearedCounters(warmUpsSinceClear = 12))
        repository.saveReadiness(ReadinessReason.AFTER_CLEAR, null, null)
        val saved = dao.readiness.single()
        assertEquals("BEFORE_CLEAR", saved.reason)
        assertEquals(3L, saved.carId)
        assertEquals(true, saved.milOn)
        assertEquals(r.monitors, DiagCodec.decodeReadiness(saved.monitors, saved.milOn, saved.dtcCount)!!.monitors)
        assertEquals("warmUps=12", saved.counters)
    }

    @Test
    fun `readiness history decodes the stored snapshots newest first`() = runTest {
        val r = ReadinessDecoder.status(intArrayOf(0x82, 0x07, 0x65, 0x00))
        repository.saveReadiness(ReadinessReason.BEFORE_CLEAR, r, ClearedCounters(warmUpsSinceClear = 12, kmSinceClear = 340))
        now += 60_000
        repository.saveReadiness(ReadinessReason.AFTER_CLEAR, r.copy(milOn = false, dtcCount = 0), ClearedCounters(warmUpsSinceClear = 0))
        val history = repository.readinessHistory(3L, 5)
        assertEquals(listOf(ReadinessReason.AFTER_CLEAR, ReadinessReason.BEFORE_CLEAR), history.map { it.reason })
        assertEquals(true, history[1].readiness!!.milOn)
        assertEquals(340, history[1].counters!!.kmSinceClear)
        assertEquals(r.monitors, history[0].readiness!!.monitors)
        assertEquals(emptyList<ReadinessSnapshot>(), repository.readinessHistory(99L, 5))
    }
}

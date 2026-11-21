package io.github.iampopovich.eculense.feature.dashboard

import io.github.iampopovich.eculense.core.data.ExtendedSignal
import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R-E follow-up: extended (OBDb) dashboard tiles reuse the TileModel states. */
class ExtendedTilesTest {
    private val soc = ExtendedSignal("obdb:TEST_SOC", "State of charge", "%", "Battery", "7E4.7EC.220101", decimals = 1)
    private val temp = ExtendedSignal("obdb:TEST_TEMP", "Battery temperature", "°C", "Battery", "7E4.7EC.220101", decimals = 0)
    private val connected = ConnectionState.Connected(AdapterEndpoint("bt", "Test", "00:00"), AdapterInfo("ELM327 v1.5", '6', true, 12.6), emptySet())
    private val identity: (Double, String) -> Pair<Double, String> = { v, u -> v to u }

    private fun build(on: Boolean, ready: List<ExtendedSignal>?, buffer: LiveBuffer = LiveBuffer(), now: Long = 10_000, disabled: Set<String> = emptySet(), state: ConnectionState = connected) =
        TileModel.buildExtended(listOf(soc.sensorId, temp.sensorId), ready, on, disabled, buffer, state, 1_000, now, identity)

    @Test
    fun `switch off or no signalset shows a dash with the extended-off note, names from the signalset`() {
        val off = build(on = false, ready = listOf(soc, temp))
        assertEquals(listOf(ValueStatus.EXTENDED_OFF, ValueStatus.EXTENDED_OFF), off.map { it.status })
        assertEquals("State of charge", off[0].extended?.name)
        assertNull(off[0].value)
        val unmatched = build(on = true, ready = null)
        assertEquals(ValueStatus.EXTENDED_OFF, unmatched[0].status)
        assertEquals("TEST_SOC", unmatched[0].extended?.name)
    }

    @Test
    fun `live, waiting, not answering and stale states like the PID tiles`() {
        val buffer = LiveBuffer()
        buffer.accept(SensorReading(soc.sensorId, 54.5, "%", 0), 9_000)
        val tiles = build(on = true, ready = listOf(soc, temp), buffer = buffer)
        assertEquals(ValueStatus.LIVE, tiles[0].status)
        assertEquals(54.5, tiles[0].value!!, 1e-9)
        assertEquals(1, tiles[0].decimals)
        assertEquals("obdb:TEST_SOC", tiles[0].sensorId)
        assertEquals(ValueStatus.NO_DATA, tiles[1].status)
        assertEquals(ValueStatus.NOT_ANSWERING, build(on = true, ready = listOf(soc, temp), disabled = setOf("7E4.7EC.220101"))[1].status)
        // 3 SLOW periods (10 cycles each) at 1 s per cycle = 30 s
        assertEquals(ValueStatus.LIVE, build(on = true, ready = listOf(soc, temp), buffer = buffer, now = 38_000)[0].status)
        val stale = build(on = true, ready = listOf(soc, temp), buffer = buffer, now = 40_000)[0]
        assertEquals(ValueStatus.STALE, stale.status)
        assertEquals(31L, stale.ageSec)
        val engineOff = build(on = true, ready = listOf(soc, temp), buffer = buffer, state = ConnectionState.EngineOff(connected.endpoint, 12.0))
        assertEquals(ValueStatus.STALE, engineOff[0].status)
    }
}

package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.DtcKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327SessionTest {
    private val transport = ScriptedTransport(FakeCar.replies)
    private val session = Elm327Session(ElmLink(transport), clock = { 1_000L })

    @Test
    fun `initialize configures adapter and detects CAN`() = runTest {
        val info = session.initialize()
        assertEquals(AdapterInfo("ELM327 v1.5", '6', isCan = true, batteryVoltage = 12.6), info)
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "0100", "ATDPN", "ATRV"), transport.sent)
    }

    @Test(expected = ObdException::class)
    fun `initialize fails when car does not answer`() = runTest {
        val silentCar = ScriptedTransport(FakeCar.replies + ("0100" to "UNABLE TO CONNECT"))
        Elm327Session(ElmLink(silentCar)).initialize()
    }

    @Test
    fun `supported pids follow the 0x20 continuation bit`() = runTest {
        session.initialize()
        val pids = session.supportedPids()
        assertTrue(0x0C in pids)
        assertTrue(0x21 in pids)
        assertTrue("0120" in transport.sent)
        assertTrue("0140" !in transport.sent) // в ответе 0120 бит «есть 0x40» не выставлен
    }

    @Test
    fun `read rpm`() = runTest {
        session.initialize()
        val reading = session.read(StandardPids.byCode(0x0C)!!)!!
        assertEquals("01.0C", reading.sensorId)
        assertEquals(1726.0, reading.value, 0.001)
        assertEquals(1_000L, reading.timestampMs)
    }

    @Test
    fun `read stored dtcs`() = runTest {
        session.initialize()
        assertEquals(listOf("P0133", "P0300"), session.readDtcs(DtcKind.STORED).map { it.code })
        assertEquals(emptyList<String>(), session.readDtcs(DtcKind.PENDING).map { it.code })
    }

    @Test
    fun `read vin from multi-frame`() = runTest {
        session.initialize()
        assertEquals("1D4GP00R55B123456", session.readVin())
    }

    @Test
    fun `clear dtcs`() = runTest {
        session.initialize()
        assertTrue(session.clearDtcs())
    }
}

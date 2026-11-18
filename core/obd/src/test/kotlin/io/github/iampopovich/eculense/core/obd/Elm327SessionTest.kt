package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.AdapterInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}

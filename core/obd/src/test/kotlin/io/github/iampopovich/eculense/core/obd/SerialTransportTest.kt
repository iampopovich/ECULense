package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SerialTransportTest {
    @Test
    fun `finds the baud rate and talks to ELM327`() = runBlocking<Unit> {
        val port = FakeSerialPort(correctBaud = 115_200)
        val transport = SerialTransport(port)
        transport.open()
        assertEquals(115_200, transport.baudRate)
        assertEquals("ELM327 v1.5\r\r", ElmLink(transport).send("ATZ"))
        transport.close()
        assertTrue(port.closed)
    }

    @Test(expected = IOException::class)
    fun `fails when adapter answers on no baud rate`() = runBlocking<Unit> {
        SerialTransport(FakeSerialPort(correctBaud = 1)).open()
    }
}

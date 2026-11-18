package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.DtcKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class EmulatorSmokeTest {
    @Test
    fun `talks to ELM327-emulator`() = runBlocking<Unit> {
        val address = System.getenv("ELM_EMULATOR")
        assumeTrue("задайте ELM_EMULATOR=host:port", address != null)
        val transport = TcpTransport(address!!.substringBefore(':'), address.substringAfter(':').toInt())
        transport.open()
        try {
            val session = Elm327Session(ElmLink(transport))
            val info = session.initialize()
            val pids = session.supportedPids()
            println("adapter=$info pids=${pids.map { "%02X".format(it) }} vin=${session.readVin()} dtc=${session.readDtcs(DtcKind.STORED)}")
            assertTrue(0x0C in pids)
            assertNotNull(session.read(StandardPids.byCode(0x0C)!!))
        } finally {
            transport.close()
        }
    }
}

package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket

class TcpTransportTest {
    @Test
    fun `round trip through a local socket`() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val fakeAdapter = launch(Dispatchers.IO) {
                server.accept().use { client ->
                    val input = client.getInputStream()
                    val command = buildString {
                        while (true) {
                            val c = input.read()
                            if (c < 0 || c == '\r'.code) break
                            append(c.toChar())
                        }
                    }
                    client.getOutputStream().apply {
                        write("ECHO $command\r\r>".toByteArray())
                        flush()
                    }
                }
            }
            val transport = TcpTransport("127.0.0.1", server.localPort)
            transport.open()
            assertEquals("ECHO ATI\r\r", ElmLink(transport).send("ATI"))
            transport.close()
            fakeAdapter.join()
        }
    }
}

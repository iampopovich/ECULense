package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/** B: TcpTransport through a network-bound SocketFactory; the lease lives for the whole session. */
class WifiBindingTest {
    /** Records every socket it creates (stands in for Network.getSocketFactory()). */
    private class RecordingFactory : SocketFactory() {
        val created = AtomicInteger()
        override fun createSocket(): Socket = Socket().also { created.incrementAndGet() }
        override fun createSocket(host: String?, port: Int) = throw UnsupportedOperationException()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = throw UnsupportedOperationException()
        override fun createSocket(host: InetAddress?, port: Int) = throw UnsupportedOperationException()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = throw UnsupportedOperationException()
    }

    private class FakeBinder(private val fail: ObdError? = null) : AdapterNetworkBinder {
        val factory = RecordingFactory()
        var acquired = 0
        var released = 0
        var onLost: (() -> Unit)? = null
        override suspend fun acquire(host: String, onLost: () -> Unit): NetworkLease {
            fail?.let { throw ObdIoException(it) }
            acquired++
            this.onLost = onLost
            return object : NetworkLease {
                override val socketFactory: SocketFactory = factory
                override fun release() { released++ }
            }
        }
    }

    /** A one-client ELM stand-in: answers every command line with "OK" until the client goes away. */
    private fun ServerSocket.serveOk() = Thread {
        runCatching {
            accept().use { client ->
                val input = client.getInputStream()
                val out = client.getOutputStream()
                while (true) {
                    val c = input.read()
                    if (c < 0) break
                    if (c == '\r'.code) { out.write("OK\r\r>".toByteArray()); out.flush() }
                }
            }
        }
    }.apply { isDaemon = true; start() }

    @Test
    fun `tcp transport creates its socket with the given factory`() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            server.serveOk()
            val factory = RecordingFactory()
            val transport = TcpTransport("127.0.0.1", server.localPort, socketFactory = factory)
            transport.open()
            assertEquals("OK\r\r", ElmLink(transport).send("ATE0"))
            transport.close()
            assertEquals(1, factory.created.get())
        }
    }

    @Test
    fun `bound transport uses the lease factory and releases the lease on close`() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            server.serveOk()
            val binder = FakeBinder()
            val transport = BoundTcpTransport("127.0.0.1", server.localPort, binder)
            transport.open()
            assertEquals("OK\r\r", ElmLink(transport).send("ATZ"))
            assertEquals(1, binder.factory.created.get())
            assertEquals(1, binder.acquired)
            assertEquals(0, binder.released)
            transport.close()
            assertEquals(1, binder.released)
        }
    }

    @Test
    fun `no adapter wifi is a typed error and nothing stays bound`() = runBlocking<Unit> {
        val binder = FakeBinder(fail = ObdError.NotOnAdapterWifi("192.168.0.10"))
        val transport = WifiTransportFactory(binder = binder).create(AdapterEndpoint("wifi", "192.168.0.10:35000", "x"))
        try {
            transport.open()
            fail("open must fail")
        } catch (e: IOException) {
            assertEquals(ObdError.NotOnAdapterWifi("192.168.0.10"), ObdError.of(e))
        }
        assertEquals(0, binder.released)
    }

    @Test
    fun `failed connect releases the lease`() = runBlocking<Unit> {
        val port = ServerSocket(0).use { it.localPort } // closed: connection refused
        val binder = FakeBinder()
        val transport = BoundTcpTransport("127.0.0.1", port, binder)
        runCatching { transport.open() }.onSuccess { fail("open must fail") }
        assertEquals(1, binder.acquired)
        assertEquals(1, binder.released)
    }

    @Test
    fun `network loss ends the read with a typed IOException`() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            server.serveOk()
            val binder = FakeBinder()
            val transport = BoundTcpTransport("127.0.0.1", server.localPort, binder)
            transport.open()
            binder.onLost!!.invoke()
            try {
                withTimeout(5_000) { transport.read() }
                fail("read must fail after the network is lost")
            } catch (e: IOException) {
                assertEquals(ObdError.AdapterWifiLost, ObdError.of(e))
            }
            transport.close()
            assertEquals(1, binder.released)
        }
    }

    @Test
    fun `simulated and loopback endpoints are never bound`() {
        val binder = FakeBinder()
        val factory = WifiTransportFactory(binder = binder)
        assertFalse(factory.create(AdapterEndpoint("wifi", "10.0.2.2:35000", "emu", EndpointLabel.PC_EMULATOR)) is BoundTcpTransport)
        assertFalse(factory.create(AdapterEndpoint("wifi", "127.0.0.1:35000", "adb reverse")) is BoundTcpTransport)
        assertTrue(factory.create(AdapterEndpoint("wifi", "192.168.0.10:35000", "x")) is BoundTcpTransport)
        assertFalse(WifiTransportFactory().create(AdapterEndpoint("wifi", "192.168.0.10:35000", "x")) is BoundTcpTransport)
    }

    /** WifiTransportFactory exempts loopback hosts from binding; the tests' local servers need the bound path. */
    private fun boundFactory(binder: AdapterNetworkBinder) = object : TransportFactory {
        override val kind = "wifi"
        override suspend fun discover() = emptyList<AdapterEndpoint>()
        override fun create(endpoint: AdapterEndpoint): ObdTransport =
            BoundTcpTransport(endpoint.address.substringBeforeLast(':'), endpoint.address.substringAfterLast(':').toInt(), binder)
    }

    /** Serves [FakeCar] over TCP to any number of clients (each on its own thread). */
    private fun ServerSocket.serveFakeCar(accepted: AtomicInteger) = Thread {
        runCatching {
            while (true) {
                val client = accept()
                accepted.incrementAndGet()
                Thread {
                    client.use { c ->
                        val input = c.getInputStream()
                        val out = c.getOutputStream()
                        val line = StringBuilder()
                        runCatching {
                            while (true) {
                                val ch = input.read()
                                if (ch < 0) break
                                if (ch == '\r'.code) {
                                    val reply = FakeCar.replies[line.toString().trim()] ?: "?"
                                    line.clear()
                                    out.write("$reply\r\r>".toByteArray()); out.flush()
                                } else {
                                    line.append(ch.toChar())
                                }
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }
            }
        }
    }.apply { isDaemon = true; start() }

    @Test
    fun `connection manager reconnects through a new lease after the wifi network is lost`() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val accepted = AtomicInteger()
            server.serveFakeCar(accepted)
            val binder = FakeBinder()
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
            val manager = ObdConnectionManager(listOf(boundFactory(binder)), scope)
            val endpoint = AdapterEndpoint("wifi", "127.0.0.1:${server.localPort}", "x")
            try {
                manager.connect(endpoint)
                withTimeout(10_000) { manager.state.first { it is ConnectionState.Connected } }
                manager.setPolledPids(listOf(StandardPids.byCode(0x0C)!!))
                val readings = launch { manager.readings.collect { } }
                kotlinx.coroutines.delay(300)
                binder.onLost!!.invoke()
                withTimeout(10_000) { manager.state.first { it is ConnectionState.Reconnecting } }
                withTimeout(15_000) { manager.state.first { it is ConnectionState.Connected } }
                assertEquals(2, accepted.get())
                assertEquals(2, binder.acquired)
                assertEquals(1, binder.released)
                readings.cancel()
                manager.disconnect()
                withTimeout(5_000) { while (binder.released < 2) kotlinx.coroutines.delay(20) }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `reconnect after a network loss opens a new transport and a new lease`() = runBlocking<Unit> {
        ServerSocket(0).use { server ->
            val accepted = AtomicInteger()
            val serving = launch(Dispatchers.IO) {
                repeat(2) {
                    val client = server.accept()
                    accepted.incrementAndGet()
                    Thread {
                        client.use { c ->
                            val input = c.getInputStream()
                            val out = c.getOutputStream()
                            runCatching {
                                while (true) {
                                    val ch = input.read()
                                    if (ch < 0) break
                                    if (ch == '\r'.code) { out.write("OK\r\r>".toByteArray()); out.flush() }
                                }
                            }
                        }
                    }.apply { isDaemon = true; start() }
                }
            }
            val binder = FakeBinder()
            val factory = boundFactory(binder)
            val endpoint = AdapterEndpoint("wifi", "127.0.0.1:${server.localPort}", "x")
            val first = factory.create(endpoint)
            first.open()
            binder.onLost!!.invoke()
            runCatching { withTimeout(5_000) { first.read() } }
            first.close()
            // What ObdConnectionManager.reconnect does: a fresh transport from the factory, not the dead socket.
            val second = factory.create(endpoint)
            second.open()
            assertEquals("OK\r\r", ElmLink(second).send("ATZ"))
            second.close()
            serving.join()
            assertEquals(2, accepted.get())
            assertEquals(2, binder.acquired)
            assertEquals(2, binder.released)
        }
    }
}

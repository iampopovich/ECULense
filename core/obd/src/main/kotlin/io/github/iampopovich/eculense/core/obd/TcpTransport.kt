package io.github.iampopovich.eculense.core.obd

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * ELM327 over TCP. [socketFactory] decides which network the socket uses: the default factory follows the system
 * default route; on Android the Wi-Fi binder passes `Network.getSocketFactory()` of the adapter's Wi-Fi so the socket
 * stays on the adapter network while everything else in the process keeps using mobile data (B, round 2).
 */
class TcpTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 5_000,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
) : StreamTransport() {
    @Volatile
    private var socket: Socket? = null

    override fun openStreams(): Pair<InputStream, OutputStream> {
        val s = socketFactory.createSocket()
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            s.tcpNoDelay = true
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
        socket = s
        return s.getInputStream() to s.getOutputStream()
    }

    override fun closeStreams() {
        socket?.close()
        socket = null
    }
}

/**
 * Wi-Fi adapters: the ELM327 Wi-Fi default address, the address from an imported Car Scanner backup ([suggested],
 * listed first when it differs from the default) and [extra] endpoints (debug builds add the PC emulator here; release
 * builds pass none, so no developer endpoint text ships in them).
 *
 * [binder] (Android: WifiNetworkBinder in :core:wifi) binds the socket of a real adapter to the Wi-Fi network the
 * adapter lives on. Simulated endpoints and loopback hosts (adb reverse) use the default socket factory.
 */
class WifiTransportFactory(
    extra: List<AdapterEndpoint> = emptyList(),
    private val suggested: suspend () -> String? = { null },
    private val binder: AdapterNetworkBinder? = null,
) : TransportFactory {
    private val endpoints = listOf(AdapterEndpoint(KIND, DEFAULT_ADDRESS, "Wi-Fi ELM327 (192.168.0.10)")) + extra

    override val kind = KIND
    override suspend fun discover(): List<AdapterEndpoint> {
        val fromBackup = runCatching { suggested() }.getOrNull()
            ?.takeIf { it != DEFAULT_ADDRESS && it.substringAfterLast(':').toIntOrNull() != null }
            ?: return endpoints
        return listOf(AdapterEndpoint(KIND, fromBackup, "Wi-Fi from Car Scanner: $fromBackup", EndpointLabel.WIFI_CAR_SCANNER)) + endpoints
    }

    override fun create(endpoint: AdapterEndpoint): ObdTransport {
        val host = endpoint.address.substringBeforeLast(':')
        val port = endpoint.address.substringAfterLast(':').toInt()
        val bind = binder
        return if (bind == null || endpoint.isSimulated || isLoopback(host)) TcpTransport(host, port) else BoundTcpTransport(host, port, bind)
    }

    private fun isLoopback(host: String) = host == "localhost" || host.startsWith("127.") || host == "::1"

    companion object {
        const val KIND = "wifi"
        const val DEFAULT_ADDRESS = "192.168.0.10:35000"
    }
}

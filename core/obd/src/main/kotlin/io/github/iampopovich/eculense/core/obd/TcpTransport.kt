package io.github.iampopovich.eculense.core.obd

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class TcpTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 5_000,
) : StreamTransport() {
    private var socket: Socket? = null

    override fun openStreams(): Pair<InputStream, OutputStream> {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.tcpNoDelay = true
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
 * listed first when it differs from the default) and optionally the PC emulator.
 */
class WifiTransportFactory(
    includeEmulator: Boolean = false,
    private val suggested: suspend () -> String? = { null },
) : TransportFactory {
    private val endpoints = buildList {
        add(AdapterEndpoint(KIND, DEFAULT_ADDRESS, "Wi-Fi ELM327 (192.168.0.10)"))
        if (includeEmulator) add(AdapterEndpoint(KIND, "10.0.2.2:35000", "ELM327 emulator on PC (10.0.2.2)", EndpointLabel.PC_EMULATOR))
    }

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
        return TcpTransport(host, port)
    }

    companion object {
        const val KIND = "wifi"
        const val DEFAULT_ADDRESS = "192.168.0.10:35000"
    }
}

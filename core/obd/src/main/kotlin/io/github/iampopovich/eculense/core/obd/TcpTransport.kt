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

class WifiTransportFactory(includeEmulator: Boolean = false) : TransportFactory {
    private val endpoints = buildList {
        add(AdapterEndpoint(KIND, "192.168.0.10:35000", "Wi-Fi ELM327 (192.168.0.10)"))
        if (includeEmulator) add(AdapterEndpoint(KIND, "10.0.2.2:35000", "Эмулятор ELM327 на ПК (10.0.2.2)"))
    }

    override val kind = KIND
    override suspend fun discover() = endpoints

    override fun create(endpoint: AdapterEndpoint): ObdTransport {
        val host = endpoint.address.substringBeforeLast(':')
        val port = endpoint.address.substringAfterLast(':').toInt()
        return TcpTransport(host, port)
    }

    companion object {
        const val KIND = "wifi"
    }
}

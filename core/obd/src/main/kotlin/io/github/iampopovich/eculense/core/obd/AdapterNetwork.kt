package io.github.iampopovich.eculense.core.obd

import javax.net.SocketFactory

/**
 * B (round 2): sockets of a Wi-Fi adapter must go through the adapter's Wi-Fi network, not the process default.
 * Android drops or deprioritises a Wi-Fi without internet and routes new sockets via mobile data, so an ELM327 at
 * 192.168.0.10 becomes unreachable. The Android implementation (:core:wifi) requests the Wi-Fi network without the
 * INTERNET capability and hands out `Network.getSocketFactory()`; it never calls `bindProcessToNetwork` (that would move
 * LLM requests and data-pack downloads onto the adapter network).
 * Sources: https://developer.android.com/reference/android/net/Network#getSocketFactory() ,
 * https://developer.android.com/reference/android/net/ConnectivityManager#bindProcessToNetwork(android.net.Network) .
 */
fun interface AdapterNetworkBinder {
    /**
     * Waits for the network the adapter at [host] is on and holds it until [NetworkLease.release]. [onLost] is called
     * once the held network goes away (its sockets are dead for good: the transport must be reopened).
     * Throws an [ObdIoException] with [ObdError.NotOnAdapterWifi] when no such network shows up in time.
     */
    suspend fun acquire(host: String, onLost: () -> Unit): NetworkLease
}

/** A held network; [release] unregisters the request (the system may then drop a Wi-Fi without internet). */
interface NetworkLease {
    val socketFactory: SocketFactory
    fun release()
}

/**
 * TCP transport whose socket is created through a [NetworkLease]. The lease lives for this transport (one
 * connection); a reconnect creates a new transport and a new lease. A lost network aborts the socket so the reader
 * fails with [ObdError.AdapterWifiLost] instead of hanging on a dead route.
 */
class BoundTcpTransport(
    private val host: String,
    private val port: Int,
    private val binder: AdapterNetworkBinder,
    private val connectTimeoutMs: Int = 5_000,
) : ObdTransport {
    @Volatile
    private var lease: NetworkLease? = null

    @Volatile
    private var delegate: TcpTransport? = null

    @Volatile
    private var lost = false

    override suspend fun open() {
        val held = binder.acquire(host) {
            lost = true
            delegate?.abort(ObdError.AdapterWifiLost)
        }
        lease = held
        val tcp = TcpTransport(host, port, connectTimeoutMs, held.socketFactory)
        delegate = tcp
        try {
            tcp.open()
        } catch (e: Exception) {
            delegate = null
            lease = null
            held.release()
            throw e
        }
        if (lost) tcp.abort(ObdError.AdapterWifiLost)
    }

    override suspend fun write(data: ByteArray) = (delegate ?: throw ObdIoException(ObdError.NotOpen)).write(data)

    override suspend fun read(): ByteArray = (delegate ?: throw ObdIoException(ObdError.NotOpen)).read()

    override suspend fun readAvailable(): ByteArray? = (delegate ?: throw ObdIoException(ObdError.NotOpen)).readAvailable()

    override suspend fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
            lease?.release()
            lease = null
        }
    }
}

/** An IPv4 address with its prefix length, as in Android's LinkAddress (IPv6 entries are ignored). */
data class IpPrefix(val address: String, val prefixLength: Int)

/** What the binder knows about one network: [handle] is the platform object (android.net.Network). */
data class NetworkCandidate<T>(
    val handle: T,
    val isWifi: Boolean,
    val isVpn: Boolean,
    val addresses: List<IpPrefix>,
    val gateways: List<String>,
)

/**
 * The Wi-Fi network the adapter at [host] is on, or null. Only Wi-Fi networks that are not a VPN count. An IPv4
 * [host] must lie inside one of the network's address prefixes or be its gateway (adapters in AP mode are usually
 * the gateway, 192.168.0.10 / 192.168.4.1). A hostname can't be checked offline: the first Wi-Fi network is taken.
 */
fun <T> pickAdapterNetwork(candidates: Collection<NetworkCandidate<T>>, host: String): T? {
    val wifi = candidates.filter { it.isWifi && !it.isVpn }
    val target = parseIpv4(host) ?: return wifi.firstOrNull()?.handle
    return wifi.firstOrNull { network ->
        network.gateways.any { parseIpv4(it) == target } ||
            network.addresses.any { prefix ->
                val address = parseIpv4(prefix.address) ?: return@any false
                val bits = prefix.prefixLength
                if (bits !in 0..32) return@any false
                val mask = if (bits == 0) 0 else -1 shl (32 - bits)
                (address and mask) == (target and mask)
            }
    }?.handle
}

/** Dotted-quad IPv4 to its 32-bit value; null for anything else (hostnames, IPv6). No DNS lookup. */
fun parseIpv4(text: String): Int? {
    val parts = text.trim().split('.')
    if (parts.size != 4) return null
    var value = 0
    for (part in parts) {
        if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
        val octet = part.toInt()
        if (octet > 255) return null
        value = (value shl 8) or octet
    }
    return value
}

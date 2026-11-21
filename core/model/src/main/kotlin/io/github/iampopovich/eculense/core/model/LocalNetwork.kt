package io.github.iampopovich.eculense.core.model

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Whether an address is on the local network, judged only by how the host is written, without DNS (a name like
 * 127.0.0.1.nip.io is not local). Shared rule for every unencrypted connection the user configures: the AI «Other
 * server» (plain http only to local hosts) and the MQTT broker (plain TCP only to local hosts, otherwise TLS).
 * Local = loopback, 10/8, 172.16/12, 192.168/16, 169.254/16 link-local, IPv6 loopback/link-local/ULA fc00::/7,
 * localhost and *.local (mDNS).
 */
object LocalNetwork {
    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val ipv6 = Regex("""^[0-9a-fA-F:.]+$""")

    fun isLocalHost(rawHost: String): Boolean {
        val host = rawHost.trim().removePrefix("[").removeSuffix("]").trimEnd('.').lowercase()
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host.endsWith(".local") && host.length > ".local".length) return true
        ipv4.matchEntire(host)?.let { m ->
            val b = m.groupValues.drop(1).map { it.toInt() }
            if (b.any { it > 255 }) return false
            return b[0] == 127 || b[0] == 10 ||
                (b[0] == 172 && b[1] in 16..31) ||
                (b[0] == 192 && b[1] == 168) ||
                (b[0] == 169 && b[1] == 254)
        }
        if (':' in host && ipv6.matches(host)) {
            // An IPv6 literal is parsed without a DNS lookup.
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet6Address ?: return false
            val first = address.address[0].toInt() and 0xFF
            return address.isLoopbackAddress || address.isLinkLocalAddress || (first and 0xFE) == 0xFC
        }
        return false
    }
}

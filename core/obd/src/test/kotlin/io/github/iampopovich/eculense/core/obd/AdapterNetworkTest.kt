package io.github.iampopovich.eculense.core.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B: choosing the Wi-Fi network the adapter lives on (pure part of the Android WifiNetworkBinder). */
class AdapterNetworkTest {
    private fun wifi(id: String, vararg addresses: String, gateway: String? = null, vpn: Boolean = false) = NetworkCandidate(
        handle = id,
        isWifi = true,
        isVpn = vpn,
        addresses = addresses.map { val (ip, len) = it.split('/'); IpPrefix(ip, len.toInt()) },
        gateways = listOfNotNull(gateway),
    )

    private fun cellular(id: String) = NetworkCandidate(id, isWifi = false, isVpn = false, addresses = listOf(IpPrefix("10.20.30.40", 8)), gateways = emptyList())

    @Test
    fun `adapter host inside the wifi subnet picks that network`() {
        val adapterAp = wifi("obd", "192.168.0.11/24", gateway = "192.168.0.10")
        assertEquals("obd", pickAdapterNetwork(listOf(cellular("lte"), adapterAp), "192.168.0.10"))
        // Host is in the subnet but is not the gateway (adapter as a client on a phone hotspot or a router).
        assertEquals("home", pickAdapterNetwork(listOf(wifi("home", "192.168.1.23/24", gateway = "192.168.1.1")), "192.168.1.50"))
    }

    @Test
    fun `gateway match counts even when the prefix does not cover the host`() {
        val odd = wifi("esp", "10.0.0.5/32", gateway = "192.168.4.1")
        assertEquals("esp", pickAdapterNetwork(listOf(odd), "192.168.4.1"))
    }

    @Test
    fun `wifi of another subnet is not the adapter network`() {
        val home = wifi("home", "192.168.1.23/24", gateway = "192.168.1.1")
        assertNull(pickAdapterNetwork(listOf(home), "192.168.0.10"))
    }

    @Test
    fun `no wifi at all gives no network`() {
        assertNull(pickAdapterNetwork(listOf(cellular("lte")), "192.168.0.10"))
        assertNull(pickAdapterNetwork(emptyList<NetworkCandidate<String>>(), "192.168.0.10"))
    }

    @Test
    fun `a vpn is never the adapter network even if its routes cover the host`() {
        val vpn = wifi("vpn", "192.168.0.2/16", gateway = "192.168.0.10", vpn = true)
        val adapterAp = wifi("obd", "192.168.0.11/24", gateway = "192.168.0.10")
        assertNull(pickAdapterNetwork(listOf(vpn), "192.168.0.10"))
        assertEquals("obd", pickAdapterNetwork(listOf(vpn, adapterAp), "192.168.0.10"))
    }

    @Test
    fun `hostname adapters take the only wifi network, ipv6 addresses are ignored`() {
        val ap = wifi("obd", "fe80::1/64", "192.168.0.11/24")
        assertEquals("obd", pickAdapterNetwork(listOf(cellular("lte"), ap), "wican.local"))
        assertEquals("obd", pickAdapterNetwork(listOf(ap), "192.168.0.10"))
    }

    @Test
    fun `ipv4 parsing rejects garbage`() {
        assertNull(parseIpv4("192.168.0"))
        assertNull(parseIpv4("192.168.0.256"))
        assertNull(parseIpv4("a.b.c.d"))
        assertEquals(0xC0A8000A.toInt(), parseIpv4("192.168.0.10"))
    }
}

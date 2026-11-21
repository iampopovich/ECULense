package io.github.iampopovich.eculense.core.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.github.iampopovich.eculense.core.obd.AdapterNetworkBinder
import io.github.iampopovich.eculense.core.obd.IpPrefix
import io.github.iampopovich.eculense.core.obd.NetworkCandidate
import io.github.iampopovich.eculense.core.obd.NetworkLease
import io.github.iampopovich.eculense.core.obd.ObdError
import io.github.iampopovich.eculense.core.obd.ObdIoException
import io.github.iampopovich.eculense.core.obd.pickAdapterNetwork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

/**
 * B (round 2): holds the adapter's Wi-Fi for one TCP connection and hands out its socket factory.
 *
 * - `requestNetwork(TRANSPORT_WIFI, without NET_CAPABILITY_INTERNET)`: also matches a Wi-Fi without internet and keeps
 *   it up while the request is registered (Android otherwise drops/leaves such a network). The callback stays
 *   registered for the whole session and is unregistered in [NetworkLease.release].
 * - The network must really be the adapter's ([pickAdapterNetwork]: host inside its subnet or its gateway); a home
 *   Wi-Fi of another subnet keeps waiting until [timeoutMs] and then fails with [ObdError.NotOnAdapterWifi].
 * - Never `bindProcessToNetwork`: LLM requests, data-pack downloads and MQTT keep using the default (mobile) network.
 *
 * Sources: https://developer.android.com/reference/android/net/ConnectivityManager#requestNetwork(android.net.NetworkRequest,%20android.net.ConnectivityManager.NetworkCallback) ,
 * https://developer.android.com/reference/android/net/Network#getSocketFactory() ,
 * https://stackoverflow.com/questions/62277005 (keeping an IoT AP without internet).
 * [timeoutMs] 10 s = time for an already associated Wi-Fi to be offered to the callback (round 2 research); how to
 * calibrate: on the real adapter, note in the traffic log how long «connect: wifi» takes until the first ATZ.
 */
class WifiNetworkBinder(
    context: Context,
    private val timeoutMs: Long = 10_000,
) : AdapterNetworkBinder {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override suspend fun acquire(host: String, onLost: () -> Unit): NetworkLease {
        val cm = connectivity ?: throw ObdIoException(ObdError.NotOnAdapterWifi(host))
        val chosen = CompletableDeferred<Network>()
        val candidates = ConcurrentHashMap<Network, NetworkCandidate<Network>>()
        val lostOnce = AtomicBoolean(false)
        val held = AtomicReference<Network?>(null)

        fun evaluate(network: Network, caps: NetworkCapabilities?, link: LinkProperties?) {
            val capabilities = caps ?: cm.getNetworkCapabilities(network) ?: return
            val properties = link ?: cm.getLinkProperties(network) ?: return
            candidates[network] = candidate(network, capabilities, properties)
            if (held.get() == null) {
                pickAdapterNetwork(candidates.values, host)?.let { if (held.compareAndSet(null, it)) chosen.complete(it) }
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = evaluate(network, null, null)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = evaluate(network, caps, null)
            override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) = evaluate(network, null, link)
            override fun onLost(network: Network) {
                candidates.remove(network)
                if (held.get() == network && lostOnce.compareAndSet(false, true)) onLost()
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.requestNetwork(request, callback)
        } catch (e: RuntimeException) {
            // SecurityException without CHANGE_NETWORK_STATE, or too many requests for this app.
            throw ObdIoException(ObdError.NotOnAdapterWifi(host), e)
        }
        val network = try {
            withTimeoutOrNull(timeoutMs) { chosen.await() }
        } catch (e: Throwable) {
            runCatching { cm.unregisterNetworkCallback(callback) }
            throw e
        }
        if (network == null) {
            runCatching { cm.unregisterNetworkCallback(callback) }
            throw ObdIoException(ObdError.NotOnAdapterWifi(host))
        }
        return object : NetworkLease {
            private val released = AtomicBoolean(false)
            override val socketFactory: SocketFactory = network.socketFactory
            override fun release() {
                if (released.compareAndSet(false, true)) runCatching { cm.unregisterNetworkCallback(callback) }
            }
        }
    }

    private fun candidate(network: Network, caps: NetworkCapabilities, link: LinkProperties) = NetworkCandidate(
        handle = network,
        isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        addresses = link.linkAddresses.filter { it.address is Inet4Address }
            .mapNotNull { a -> a.address.hostAddress?.let { IpPrefix(it, a.prefixLength) } },
        gateways = link.routes.mapNotNull { r -> r.gateway?.takeIf { it is Inet4Address && !it.isAnyLocalAddress }?.hostAddress },
    )
}

/** Registers the binder; WifiTransportFactory (obdModule) picks it up via getOrNull. Without this module sockets use the default network. */
val wifiModule = module {
    single<AdapterNetworkBinder> { WifiNetworkBinder(androidContext()) }
}

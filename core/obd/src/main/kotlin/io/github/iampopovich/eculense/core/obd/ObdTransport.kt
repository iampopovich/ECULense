package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.withTimeoutOrNull

/** Channel to the adapter. One instance = one connection (not reused after close()). */
interface ObdTransport {
    suspend fun open()
    suspend fun write(data: ByteArray)
    /** Waits until at least one byte arrives. Throws IOException if the link is lost. */
    suspend fun read(): ByteArray

    /**
     * Bytes that already arrived, without waiting; null when nothing is pending. Used to drop stale replies before a
     * new command. The default polls [read] for a few milliseconds; channel-based transports override it to be instant.
     */
    suspend fun readAvailable(): ByteArray? = withTimeoutOrNull(DRAIN_POLL_MS) { read() }

    suspend fun close()

    companion object {
        const val DRAIN_POLL_MS = 5L
    }
}

/**
 * An adapter that can be connected to. kind: which factory creates the transport. label/address: device name and
 * address shown as is, or English fallbacks when [labelKey] marks a built-in endpoint the UI names itself.
 */
data class AdapterEndpoint(val kind: String, val address: String, val label: String, val labelKey: EndpointLabel? = null)

/** Built-in endpoints whose label and description are app text (localized in :core:obdtext). */
enum class EndpointLabel { DEMO, PC_EMULATOR }

/** Each link type (BT, Wi-Fi, BLE) is a separate factory, registered in Koin via bind TransportFactory::class. */
interface TransportFactory {
    val kind: String
    suspend fun discover(): List<AdapterEndpoint>
    fun create(endpoint: AdapterEndpoint): ObdTransport
}

package io.github.iampopovich.eculense.core.obd

/** Channel to the adapter. One instance = one connection (not reused after close()). */
interface ObdTransport {
    suspend fun open()
    suspend fun write(data: ByteArray)
    /** Waits until at least one byte arrives. Throws IOException if the link is lost. */
    suspend fun read(): ByteArray
    suspend fun close()
}

/** An adapter that can be connected to. kind: which factory creates the transport. */
data class AdapterEndpoint(val kind: String, val address: String, val label: String)

/** Each link type (BT, Wi-Fi, BLE) is a separate factory, registered in Koin via bind TransportFactory::class. */
interface TransportFactory {
    val kind: String
    suspend fun discover(): List<AdapterEndpoint>
    fun create(endpoint: AdapterEndpoint): ObdTransport
}

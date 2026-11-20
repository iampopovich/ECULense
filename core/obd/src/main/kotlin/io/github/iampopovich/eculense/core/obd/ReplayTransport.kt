package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.channels.Channel

/**
 * Plays an exported traffic log ([ObdTrafficLog.export]) back as an adapter: each command gets the reply that was
 * recorded for it. Matching is in recorded order; when the next recorded command differs, the log is scanned for the
 * same command (forward first, then from the start). Unknown commands get "?" like a real ELM327.
 * DROP and NOTE entries are not replayed: they were not answers to a command.
 */
class ReplayTransport(exported: String) : ObdTransport {
    private class Exchange(val command: String, val reply: ByteArray)

    private val exchanges: List<Exchange> = buildExchanges(TrafficText.parse(exported))
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private var cursor = 0
    private val pending = StringBuilder()

    val size: Int get() = exchanges.size

    override suspend fun open() = Unit

    override suspend fun write(data: ByteArray) {
        pending.append(data.decodeToString())
        while (true) {
            val end = pending.indexOf("\r")
            if (end < 0) break
            val command = normalize(pending.substring(0, end))
            pending.delete(0, end + 1)
            if (command.isNotEmpty()) incoming.send(replyTo(command))
        }
    }

    private fun replyTo(command: String): ByteArray {
        val index = (cursor until exchanges.size).firstOrNull { exchanges[it].command == command }
            ?: (0 until cursor).firstOrNull { exchanges[it].command == command }
            ?: return "?\r\r>".encodeToByteArray()
        cursor = index + 1
        return exchanges[index].reply
    }

    override suspend fun read(): ByteArray = incoming.receive()

    override suspend fun readAvailable(): ByteArray? = incoming.tryReceive().getOrNull()

    override suspend fun close() {
        incoming.close()
    }

    private companion object {
        fun normalize(command: String) = command.replace(" ", "").trim().uppercase()

        fun buildExchanges(entries: List<TrafficEntry>): List<Exchange> {
            val result = mutableListOf<Exchange>()
            var command: String? = null
            val reply = java.io.ByteArrayOutputStream()
            fun flush() {
                command?.let { result += Exchange(it, reply.toByteArray()) }
                reply.reset()
            }
            for (entry in entries) {
                when (entry.direction) {
                    TrafficDirection.TX -> {
                        flush()
                        command = normalize(TrafficText.unescape(entry.text).decodeToString())
                    }
                    TrafficDirection.RX -> if (command != null) reply.write(TrafficText.unescape(entry.text))
                    TrafficDirection.DROP, TrafficDirection.NOTE -> Unit
                }
            }
            flush()
            return result
        }
    }
}

/** Offers one exported log as a selectable adapter (kind "replay"). */
class ReplayTransportFactory(private val label: String, private val exported: () -> String?) : TransportFactory {
    override val kind = KIND
    override suspend fun discover(): List<AdapterEndpoint> =
        if (exported().isNullOrBlank()) emptyList() else listOf(AdapterEndpoint(KIND, "traffic log", label))

    override fun create(endpoint: AdapterEndpoint): ObdTransport = ReplayTransport(exported().orEmpty())

    companion object {
        const val KIND = "replay"
    }
}

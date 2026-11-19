package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.channels.Channel

/**
 * Answers commands from a dictionary. Unknown command → "?" (like a real ELM327).
 * chunkSize splits the response into pieces — so tests show that ElmLink assembles a response from several read() calls.
 * silent = true — the adapter stays silent (timeout check).
 */
class ScriptedTransport(
    private val replies: Map<String, String>,
    private val chunkSize: Int = 64,
    private val silent: Boolean = false,
) : ObdTransport {
    val sent = mutableListOf<String>()
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    override suspend fun open() = Unit

    override suspend fun write(data: ByteArray) {
        val command = data.decodeToString().trim()
        sent += command
        if (silent) return
        val reply = "${replies[command] ?: "?"}\r\r>".encodeToByteArray()
        reply.toList().chunked(chunkSize).forEach { incoming.send(it.toByteArray()) }
    }

    override suspend fun read(): ByteArray = incoming.receive()

    override suspend fun close() {
        incoming.close()
    }
}

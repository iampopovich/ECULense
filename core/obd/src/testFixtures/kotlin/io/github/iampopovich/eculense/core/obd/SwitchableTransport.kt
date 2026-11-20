package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Scripted ELM327 whose behaviour can change during a test: [replies] is mutable, [silent] stops all answers,
 * [breakLink] makes every further read/write/open fail with an IOException (BT link lost).
 */
class SwitchableTransport(initial: Map<String, String> = FakeCar.replies) : ObdTransport {
    val replies: MutableMap<String, String> = initial.toMutableMap()
    val sent = mutableListOf<String>()

    @Volatile var silent = false

    /** Adapter answer time; > 0 lets polling advance virtual time in tests. */
    @Volatile var latencyMs = 0L

    @Volatile var broken = false
        private set
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    fun breakLink() {
        broken = true
        incoming.close(IOException("link lost"))
    }

    override suspend fun open() {
        if (broken) throw IOException("cannot open")
    }

    override suspend fun write(data: ByteArray) {
        if (broken) throw IOException("link lost")
        val command = data.decodeToString().trim()
        sent += command
        if (silent) return
        if (latencyMs > 0) delay(latencyMs)
        incoming.send("${replies[command] ?: "?"}\r\r>".encodeToByteArray())
    }

    override suspend fun read(): ByteArray = incoming.receive()

    override suspend fun readAvailable(): ByteArray? {
        if (broken) throw IOException("link lost")
        return incoming.tryReceive().getOrNull()
    }

    override suspend fun close() {
        incoming.close()
    }
}

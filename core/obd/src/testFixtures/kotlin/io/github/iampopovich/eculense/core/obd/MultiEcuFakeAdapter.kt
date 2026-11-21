package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * R-D: an adapter that knows the header state (ATH0/1, ATSH, ATCRA). With headers off it answers from [normal]
 * (like [ScriptedTransport]); with headers on from [headered], keyed by «<current header>|<command>» (functional =
 * the protocol's default header, e.g. «7DF|0100»). Commands starting with a prefix in [questionMark] answer '?'
 * (a clone without ATCRA). [writes] records every OBD request with the header it was sent under, so tests can prove
 * that nothing reached a blocked address.
 */
class MultiEcuFakeAdapter(
    private val normal: Map<String, String> = FakeCar.replies,
    private val headered: Map<String, String> = emptyMap(),
    private val questionMark: Set<String> = emptySet(),
    private val defaultHeader: String = "7DF",
    /** Answer time; > 0 lets a live poller advance virtual time in manager tests. */
    private val latencyMs: Long = 0,
) : ObdTransport {
    val sent = mutableListOf<String>()
    val writes = mutableListOf<Pair<String, String>>()
    var headers = false
        private set
    var header = defaultHeader
        private set
    var receiveFilter: String? = null
        private set
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    override suspend fun open() = Unit

    override suspend fun write(data: ByteArray) {
        val command = data.decodeToString().trim()
        sent += command
        val reply = when {
            questionMark.any { command.startsWith(it) } -> "?"
            command == "ATH1" -> { headers = true; "OK" }
            command == "ATH0" -> { headers = false; "OK" }
            command.startsWith("ATSH") -> { header = command.removePrefix("ATSH"); "OK" }
            command == "ATCRA" -> { receiveFilter = null; "OK" }
            command.startsWith("ATCRA") -> { receiveFilter = command.removePrefix("ATCRA"); "OK" }
            command.startsWith("AT") && !headers -> normal[command] ?: "OK"
            command.startsWith("AT") -> "OK"
            headers -> {
                writes += header to command
                headered["$header|$command"] ?: "NO DATA"
            }
            else -> {
                writes += header to command
                normal[command] ?: "?"
            }
        }
        if (latencyMs > 0) delay(latencyMs)
        incoming.send("$reply\r\r>".encodeToByteArray())
    }

    override suspend fun read(): ByteArray = incoming.receive()

    override suspend fun readAvailable(): ByteArray? = incoming.tryReceive().getOrNull()

    override suspend fun close() {
        incoming.close()
    }
}

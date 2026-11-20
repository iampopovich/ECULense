package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One scripted answer of [FlakyCloneTransport]. */
sealed interface CloneReply {
    /** Normal reply: [text] + "\r\r>". */
    data class Text(val text: String) : CloneReply

    /** Exact bytes, no prompt added (garbage, half replies). */
    class Raw(val bytes: ByteArray) : CloneReply

    /** [reply] arrives only after [delayMs] (asynchronously: the write returns at once). */
    data class Late(val delayMs: Long, val reply: CloneReply) : CloneReply

    /** Pieces delivered in order, each after its own pause in ms (a reply split by long silences). */
    class Parts(val parts: List<Pair<Long, String>>) : CloneReply

    /** Nothing at all. */
    data object Silent : CloneReply
}

/**
 * Scripted ELM327 clone that misbehaves on demand. Defaults come from [replies] (like [ScriptedTransport]);
 * [on] queues one-shot replies for the next sends of a command. Late replies are delivered from [scope]
 * (in tests: `backgroundScope`, so delays run on virtual time).
 */
class FlakyCloneTransport(
    private val scope: CoroutineScope,
    private val replies: Map<String, String> = FakeCar.replies,
) : ObdTransport {
    val sent = mutableListOf<String>()
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val scripted = mutableMapOf<String, ArrayDeque<CloneReply>>()

    fun on(command: String, vararg once: CloneReply): FlakyCloneTransport = apply {
        scripted.getOrPut(command) { ArrayDeque() }.addAll(once)
    }

    override suspend fun open() = Unit

    override suspend fun write(data: ByteArray) {
        val command = data.decodeToString().trim()
        sent += command
        val reply = scripted[command]?.removeFirstOrNull() ?: CloneReply.Text(replies[command] ?: "?")
        deliver(reply)
    }

    private suspend fun deliver(reply: CloneReply) {
        when (reply) {
            is CloneReply.Text -> incoming.send("${reply.text}\r\r>".encodeToByteArray())
            is CloneReply.Raw -> incoming.send(reply.bytes)
            is CloneReply.Late -> scope.launch {
                delay(reply.delayMs)
                deliver(reply.reply)
            }
            is CloneReply.Parts -> scope.launch {
                for ((pause, text) in reply.parts) {
                    delay(pause)
                    incoming.send(text.encodeToByteArray())
                }
            }
            CloneReply.Silent -> Unit
        }
    }

    override suspend fun read(): ByteArray = incoming.receive()

    override suspend fun readAvailable(): ByteArray? = incoming.tryReceive().getOrNull()

    override suspend fun close() {
        incoming.close()
    }
}

/** Misbehaviour seen on real clones, as ready transports (synthetic data only). */
object CloneScenarios {
    /** 010C answers after 2.3 s: past the 2 s command timeout, but within the resync window. */
    fun lateReplyAfterTimeout(scope: CoroutineScope) = FlakyCloneTransport(scope)
        .on("010C", CloneReply.Late(2_300, CloneReply.Text("410C1AF8")))

    /** 010C answers after 5 s: after the resync window, so it lands in front of the next command's reply. */
    fun veryLateReply(scope: CoroutineScope) = FlakyCloneTransport(scope)
        .on("010C", CloneReply.Late(5_000, CloneReply.Text("410C1AF8")))

    /** 0xFC and 0x00 before the version string on ATZ, plus a '+' verbose line of a clone. */
    fun garbageBeforeVersion(scope: CoroutineScope) = FlakyCloneTransport(scope)
        .on("ATZ", CloneReply.Raw(byteArrayOf(0xFC.toByte(), 0x00) + "\r+CONNECTED\rELM327 v1.5\r\r>".encodeToByteArray()))

    /** K-Line init fails on the first request. */
    fun busInitError(scope: CoroutineScope) = FlakyCloneTransport(scope, FakeCar.replies + ("0100" to "BUS INIT: ...ERROR"))

    /** ATDPN = "0" although the car answered; only ISO 9141 (ATTP3) answers 0100 during the fallback. */
    fun atdpnZero(scope: CoroutineScope): FlakyCloneTransport {
        val replies = FakeCar.replies + ("ATDPN" to "0") +
            listOf("6", "8", "1", "7", "9", "2", "3", "4", "5", "A").associate { "ATTP$it" to "OK" }
        val transport = FlakyCloneTransport(scope, replies)
        transport.on("0100", CloneReply.Text("SEARCHING...\r4100BE3FA813")) // auto search
        repeat(5) { transport.on("0100", CloneReply.Text("UNABLE TO CONNECT")) } // ATTP 6, 8, 1, 7, 9
        transport.on("0100", CloneReply.Text("BUS INIT: ...ERROR")) // ATTP2
        transport.on("0100", CloneReply.Text("BUS INIT: ...OK\r4100BE3FA813")) // ATTP3
        return transport
    }

    /** SEARCHING..., 4 s of silence (longer than the idle timeout), then UNABLE TO CONNECT. */
    fun searchingThenUnable(scope: CoroutineScope) = FlakyCloneTransport(scope)
        .on("0100", CloneReply.Parts(listOf(0L to "SEARCHING...\r", 4_000L to "UNABLE TO CONNECT\r\r>")))

    /** The first 010D is interrupted (STOPPED), the retry answers. */
    fun stopped(scope: CoroutineScope) = FlakyCloneTransport(scope).on("010D", CloneReply.Text("STOPPED"))

    /** Data arrives but the '>' never does: a hung adapter. */
    fun hungMidReply(scope: CoroutineScope) = FlakyCloneTransport(scope)
        .on("010D", CloneReply.Raw("410D".encodeToByteArray()))
}

package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * Command → response up to the '>' prompt. Strictly one command at a time (Mutex): ELM327 cannot do them in parallel.
 *
 * Keeps request and reply in step even when the adapter is late:
 * - bytes that arrived before a command (a late reply to an earlier one) are dropped before the write;
 * - after a timeout the link waits briefly for the pending '>' and drops it ([resyncIdleMs] of silence, at most
 *   [resyncCapMs]), so the next command does not receive the previous command's reply;
 * - data without '>' followed by [idleTimeoutMs] of silence means a hung adapter (typed timeout, then resync).
 * Received bytes are reduced to printable ASCII + CR/LF (clones put 0xFC/0x00 before the version string).
 */
internal class ElmLink(
    private val transport: ObdTransport,
    private val traffic: ObdTrafficLog? = null,
    private val idleTimeoutMs: Long = 1_500,
    private val resyncIdleMs: Long = 500,
    private val resyncCapMs: Long = 1_500,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val buffer = StringBuilder()

    /**
     * Sends [command] and returns the text before the next '>'. [idle] = false disables the inter-byte idle timeout
     * (ATZ, protocol search: the adapter legitimately pauses after "SEARCHING..." / "BUS INIT: ...").
     */
    suspend fun send(command: String, timeoutMs: Long = 2_000, idle: Boolean = true): String = mutex.withLock {
        dropPending()
        val bytes = "$command\r".encodeToByteArray()
        traffic?.record(TrafficDirection.TX, bytes)
        val started = clock()
        transport.write(bytes)
        awaitReply(command, timeoutMs, idle, started)
    }

    /** Reads one more '>'-terminated reply without writing (the own reply after a foreign one); null on timeout. */
    suspend fun awaitNextReply(command: String, timeoutMs: Long = 2_000): String? = mutex.withLock {
        try {
            awaitReply(command, timeoutMs, idle = true, started = clock())
        } catch (e: ElmTimeoutException) {
            null
        }
    }

    /** Drops everything that already arrived (after ATZ a clone may still print banners). */
    suspend fun drain() = mutex.withLock { dropPending() }

    /** A link event for the traffic log (fallbacks, retries, dropped foreign frames). */
    fun note(text: String) {
        traffic?.note(text)
    }

    private class Hung : Exception()

    private suspend fun awaitReply(command: String, timeoutMs: Long, idle: Boolean, started: Long): String {
        val received = ByteArrayOutputStream()
        var hung = false
        val reply = try {
            withTimeoutOrNull(timeoutMs) { readUntilPrompt(idle, received) }
        } catch (e: Hung) {
            hung = true
            null
        }
        if (received.size() > 0) traffic?.record(TrafficDirection.RX, received.toByteArray(), clock() - started)
        if (reply != null) return reply
        traffic?.note(if (hung) "'$command': no '>' after $idleTimeoutMs ms of silence" else "'$command': timeout after $timeoutMs ms")
        resync()
        throw ElmTimeoutException(command)
    }

    private suspend fun readUntilPrompt(idle: Boolean, received: ByteArrayOutputStream): String {
        while (true) {
            val idx = buffer.indexOf(">")
            if (idx >= 0) {
                val raw = buffer.substring(0, idx)
                buffer.delete(0, idx + 1)
                return raw
            }
            val chunk = if (idle && hasPayload(buffer)) {
                withTimeoutOrNull(idleTimeoutMs) { transport.read() } ?: throw Hung()
            } else {
                transport.read()
            }
            received.write(chunk)
            buffer.append(clean(chunk))
        }
    }

    /** Waits for the '>' that a late adapter still owes and drops it together with everything before it. */
    private suspend fun resync() {
        val dropped = ByteArrayOutputStream()
        withTimeoutOrNull(resyncCapMs) {
            while ('>' !in buffer) {
                val chunk = withTimeoutOrNull(resyncIdleMs) { transport.read() } ?: break
                dropped.write(chunk)
                buffer.append(clean(chunk))
            }
        }
        buffer.clear()
        if (dropped.size() > 0) traffic?.record(TrafficDirection.DROP, dropped.toByteArray())
    }

    private suspend fun dropPending() {
        val dropped = ByteArrayOutputStream()
        while (true) {
            val chunk = transport.readAvailable() ?: break
            if (chunk.isEmpty()) break
            dropped.write(chunk)
        }
        if (buffer.isNotEmpty() || dropped.size() > 0) {
            traffic?.record(TrafficDirection.DROP, buffer.toString().encodeToByteArray() + dropped.toByteArray())
        }
        buffer.clear()
    }

    companion object {
        private val progress = listOf("SEARCHING...", "BUS INIT:", "BUS INIT", "...")

        /** Printable ASCII, CR and LF only. */
        fun clean(bytes: ByteArray): String = buildString(bytes.size) {
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                if (c == 0x0D || c == 0x0A || c in 0x20..0x7E) append(c.toChar())
            }
        }

        /** True when the buffer holds more than protocol-search progress (only then silence means a hang). */
        fun hasPayload(buffer: CharSequence): Boolean {
            var text = buffer.toString()
            for (p in progress) text = text.replace(p, "")
            return text.any { !it.isWhitespace() && it != '.' }
        }
    }
}

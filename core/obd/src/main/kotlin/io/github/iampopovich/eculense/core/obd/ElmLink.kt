package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Command → response up to the '>' prompt. Strictly one command at a time (Mutex): ELM327 cannot do them in parallel. */
internal class ElmLink(private val transport: ObdTransport) {
    private val mutex = Mutex()
    private val buffer = StringBuilder()

    suspend fun send(command: String, timeoutMs: Long = 2_000): String = mutex.withLock {
        transport.write("$command\r".encodeToByteArray())
        try {
            withTimeout(timeoutMs) { readUntilPrompt() }
        } catch (e: TimeoutCancellationException) {
            buffer.clear()
            throw ElmTimeoutException(command)
        }
    }

    private suspend fun readUntilPrompt(): String {
        while (true) {
            val idx = buffer.indexOf(">")
            if (idx >= 0) {
                val raw = buffer.substring(0, idx)
                buffer.delete(0, idx + 1)
                return raw
            }
            buffer.append(transport.read().decodeToString())
        }
    }
}

package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Base for transports over InputStream/OutputStream (Bluetooth SPP, TCP). */
abstract class StreamTransport : ObdTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var output: OutputStream? = null

    /** Set by [abort]: the reason the reader reports instead of the socket's own "socket closed". */
    @Volatile
    private var abortedWith: ObdError? = null

    /** Blocking connection open; called on Dispatchers.IO. */
    protected abstract fun openStreams(): Pair<InputStream, OutputStream>

    /** Close the socket. This also unblocks read() in the background reader. */
    protected abstract fun closeStreams()

    override suspend fun open() {
        val (input, out) = withContext(Dispatchers.IO) { openStreams() }
        output = out
        scope.launch {
            val buffer = ByteArray(1024)
            try {
                while (isActive) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) chunks.send(buffer.copyOf(n))
                }
                chunks.close(ObdIoException(ObdError.AdapterClosed))
            } catch (e: IOException) {
                chunks.close(abortedWith?.let { ObdIoException(it, e) } ?: e)
            }
        }
    }

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            val out = output ?: throw ObdIoException(ObdError.NotOpen)
            out.write(data)
            out.flush()
        }
    }

    override suspend fun read(): ByteArray = chunks.receive()

    override suspend fun readAvailable(): ByteArray? = chunks.tryReceive().getOrNull()

    /**
     * Kills the connection from outside (e.g. its network went away): the pending and every further read fails with an
     * [ObdIoException] carrying [reason], so the connection manager reconnects instead of waiting on a dead socket.
     */
    fun abort(reason: ObdError) {
        abortedWith = reason
        runCatching { closeStreams() }
        chunks.close(ObdIoException(reason))
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) { runCatching { closeStreams() } }
        scope.cancel()
        chunks.close()
    }
}

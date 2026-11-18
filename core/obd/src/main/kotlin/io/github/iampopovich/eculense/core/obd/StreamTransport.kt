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

/** База для транспортов поверх InputStream/OutputStream (Bluetooth SPP, TCP). */
abstract class StreamTransport : ObdTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var output: OutputStream? = null

    /** Блокирующее открытие соединения; вызывается на Dispatchers.IO. */
    protected abstract fun openStreams(): Pair<InputStream, OutputStream>

    /** Закрыть сокет. Это же разблокирует read() в фоновом чтении. */
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
                chunks.close(IOException("Адаптер закрыл соединение"))
            } catch (e: IOException) {
                chunks.close(e)
            }
        }
    }

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) {
            val out = output ?: throw IOException("Транспорт не открыт")
            out.write(data)
            out.flush()
        }
    }

    override suspend fun read(): ByteArray = chunks.receive()

    override suspend fun close() {
        withContext(Dispatchers.IO) { runCatching { closeStreams() } }
        scope.cancel()
        chunks.close()
    }
}

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

/**
 * Минимум от последовательного порта. Реализации: Android USB (:core:usb, usb-serial-for-android),
 * в будущем ПК (jSerialComm на JVM или flutter_libserialport в Dart-порте).
 * Все методы блокирующие, вызываются на Dispatchers.IO.
 */
interface SerialPortIo {
    fun open()
    fun setBaudRate(baud: Int)
    fun write(data: ByteArray, timeoutMs: Int)

    /** Число прочитанных байт; 0 — если за timeoutMs ничего не пришло. */
    fun read(buffer: ByteArray, timeoutMs: Int): Int
    fun close()
}

class SerialTransport(
    private val port: SerialPortIo,
    private val baudCandidates: List<Int> = listOf(38_400, 115_200, 9_600, 57_600, 230_400),
) : ObdTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)

    var baudRate: Int? = null
        private set

    override suspend fun open() {
        withContext(Dispatchers.IO) {
            port.open()
            baudRate = baudCandidates.firstOrNull { probe(it) } ?: run {
                port.close()
                throw IOException("ELM327 не отвечает ни на одной скорости: $baudCandidates")
            }
        }
        scope.launch {
            val buffer = ByteArray(256)
            try {
                while (isActive) {
                    val n = port.read(buffer, READ_TIMEOUT_MS)
                    if (n > 0) chunks.send(buffer.copyOf(n))
                }
            } catch (e: IOException) {
                chunks.close(e)
            }
        }
    }

    /** Пустая строка: живой ELM327 на правильной скорости отвечает приглашением '>'. */
    private fun probe(baud: Int): Boolean {
        port.setBaudRate(baud)
        drain()
        port.write("\r".encodeToByteArray(), WRITE_TIMEOUT_MS)
        val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
        val buffer = ByteArray(64)
        val seen = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            val n = port.read(buffer, 100)
            if (n > 0) {
                seen.append(buffer.decodeToString(0, n))
                if ('>' in seen) return true
            }
        }
        return false
    }

    private fun drain() {
        val buffer = ByteArray(64)
        while (port.read(buffer, 50) > 0) Unit
    }

    override suspend fun write(data: ByteArray) {
        withContext(Dispatchers.IO) { port.write(data, WRITE_TIMEOUT_MS) }
    }

    override suspend fun read(): ByteArray = chunks.receive()

    override suspend fun close() {
        scope.cancel()
        withContext(Dispatchers.IO) { runCatching { port.close() } }
        chunks.close()
    }

    private companion object {
        const val READ_TIMEOUT_MS = 200
        const val WRITE_TIMEOUT_MS = 1_000
        const val PROBE_TIMEOUT_MS = 600L
    }
}

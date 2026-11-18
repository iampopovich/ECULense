package io.github.iampopovich.eculense.core.obd

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class FakeSerialPort(
    private val correctBaud: Int,
    private val replies: Map<String, String> = FakeCar.replies,
) : SerialPortIo {
    private var baud = 0
    private val pending = LinkedBlockingQueue<Byte>()
    private val line = StringBuilder()
    var closed = false
        private set

    override fun open() = Unit

    override fun setBaudRate(baud: Int) {
        this.baud = baud
    }

    override fun write(data: ByteArray, timeoutMs: Int) {
        if (baud != correctBaud) {
            "\u00ff\u00fe".encodeToByteArray().forEach(pending::put)
            return
        }
        for (b in data) {
            val c = b.toInt().toChar()
            if (c != '\r') {
                line.append(c)
                continue
            }
            val command = line.toString().trim()
            line.clear()
            val reply = if (command.isEmpty()) ">" else "${replies[command] ?: "?"}\r\r>"
            reply.encodeToByteArray().forEach(pending::put)
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val first = pending.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
        buffer[0] = first
        var n = 1
        while (n < buffer.size) {
            buffer[n] = pending.poll() ?: break
            n++
        }
        return n
    }

    override fun close() {
        closed = true
    }
}

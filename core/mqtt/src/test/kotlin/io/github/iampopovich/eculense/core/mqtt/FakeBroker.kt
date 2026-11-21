package io.github.iampopovich.eculense.core.mqtt

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Minimal MQTT 3.1.1 broker for JVM tests on a loopback ServerSocket. It decodes packets with its own code (not
 * [MqttCodec.readPacket]) so encoder bugs are not mirrored. Behaviour knobs: [connackBytes] (raw CONNACK, e.g. a
 * refusal or a malformed one), [ackQos1], [answerPings]. [dropClients] closes every open link (broker crash).
 */
class FakeBroker : AutoCloseable {
    data class Connect(
        val protocol: String,
        val level: Int,
        val cleanSession: Boolean,
        val keepAliveSec: Int,
        val clientId: String,
        val willTopic: String?,
        val willPayload: String?,
        val willQos: Int,
        val willRetain: Boolean,
        val username: String?,
        val password: String?,
    )

    data class Publish(val topic: String, val payload: String, val qos: Int, val retain: Boolean, val dup: Boolean, val packetId: Int?)

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    @Volatile var connackBytes: ByteArray = byteArrayOf(0x20, 0x02, 0x00, 0x00)
    @Volatile var ackQos1: Boolean = true
    @Volatile var answerPings: Boolean = true

    val connects = CopyOnWriteArrayList<Connect>()
    val publishes = CopyOnWriteArrayList<Publish>()
    val pingTimes = CopyOnWriteArrayList<Long>()
    val disconnects = AtomicInteger()
    val accepted = AtomicInteger()
    private val clients = CopyOnWriteArrayList<Socket>()

    /** Retained messages by topic, like a real broker keeps them (empty payload clears). */
    val retained = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        thread(isDaemon = true, name = "fake-broker") {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                accepted.incrementAndGet()
                clients += s
                thread(isDaemon = true) { serve(s) }
            }
        }
    }

    fun dropClients() {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    override fun close() {
        dropClients()
        server.close()
    }

    fun awaitPublishes(count: Int, timeoutMs: Long = 5_000): List<Publish> {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (publishes.size < count && System.nanoTime() < end) Thread.sleep(10)
        return publishes.toList()
    }

    fun await(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition() && System.nanoTime() < end) Thread.sleep(10)
        return condition()
    }

    private fun serve(s: Socket) {
        try {
            val input = DataInputStream(s.getInputStream())
            val out = s.getOutputStream()
            while (true) {
                val first = input.read()
                if (first < 0) return
                val body = ByteArray(remaining(input))
                input.readFully(body)
                when (first ushr 4) {
                    1 -> {
                        connects += parseConnect(body)
                        out.write(connackBytes)
                        out.flush()
                        if (connackBytes.size >= 4 && connackBytes[3].toInt() != 0) return
                    }
                    3 -> {
                        val qos = (first shr 1) and 0x03
                        val topicLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                        val topic = String(body, 2, topicLen, Charsets.UTF_8)
                        var at = 2 + topicLen
                        var id: Int? = null
                        if (qos > 0) {
                            id = ((body[at].toInt() and 0xFF) shl 8) or (body[at + 1].toInt() and 0xFF)
                            at += 2
                        }
                        val payload = String(body, at, body.size - at, Charsets.UTF_8)
                        val retain = first and 0x01 != 0
                        publishes += Publish(topic, payload, qos, retain, first and 0x08 != 0, id)
                        if (retain) if (payload.isEmpty()) retained.remove(topic) else retained[topic] = payload
                        if (qos == 1 && ackQos1) {
                            out.write(byteArrayOf(0x40, 0x02, (id!! shr 8).toByte(), id.toByte()))
                            out.flush()
                        }
                    }
                    12 -> {
                        pingTimes += System.currentTimeMillis()
                        if (answerPings) {
                            out.write(byteArrayOf(0xD0.toByte(), 0x00))
                            out.flush()
                        }
                    }
                    14 -> {
                        disconnects.incrementAndGet()
                        return
                    }
                }
            }
        } catch (_: Exception) {
            // link dropped
        } finally {
            runCatching { s.close() }
        }
    }

    private fun remaining(input: InputStream): Int {
        var multiplier = 1
        var value = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException()
            value += (b and 0x7F) * multiplier
            if (b and 0x80 == 0) return value
            multiplier *= 128
        }
    }

    private fun parseConnect(body: ByteArray): Connect {
        var at = 0
        fun u16(): Int = (((body[at].toInt() and 0xFF) shl 8) or (body[at + 1].toInt() and 0xFF)).also { at += 2 }
        fun bytes(): ByteArray { val n = u16(); return body.copyOfRange(at, at + n).also { at += n } }
        fun str() = String(bytes(), Charsets.UTF_8)
        val protocol = str()
        val level = body[at++].toInt()
        val flags = body[at++].toInt() and 0xFF
        val keepAlive = u16()
        val clientId = str()
        val willFlag = flags and 0x04 != 0
        val willTopic = if (willFlag) str() else null
        val willPayload = if (willFlag) String(bytes(), Charsets.UTF_8) else null
        val username = if (flags and 0x80 != 0) str() else null
        val password = if (flags and 0x40 != 0) String(bytes(), Charsets.UTF_8) else null
        return Connect(protocol, level, flags and 0x02 != 0, keepAlive, clientId, willTopic, willPayload, (flags shr 3) and 0x03, flags and 0x20 != 0, username, password)
    }
}

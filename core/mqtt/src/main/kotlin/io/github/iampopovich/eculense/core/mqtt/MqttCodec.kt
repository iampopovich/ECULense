package io.github.iampopovich.eculense.core.mqtt

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * MQTT 3.1.1 packet codec (OASIS standard, http://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html).
 * Only what a publish-only client sends and receives: CONNECT (3.1), CONNACK (3.2), PUBLISH (3.3), PUBACK (3.4),
 * PINGREQ/PINGRESP (3.12/3.13), DISCONNECT (3.14). Remaining length = 1..4 byte varint (2.2.3), strings = 2-byte
 * big-endian length + UTF-8 (1.5.3).
 */
object MqttCodec {
    const val CONNECT = 1
    const val CONNACK = 2
    const val PUBLISH = 3
    const val PUBACK = 4
    const val PINGREQ = 12
    const val PINGRESP = 13
    const val DISCONNECT = 14

    /** 2.2.3: the largest remaining length 4 bytes can carry. */
    const val MAX_REMAINING_LENGTH = 268_435_455

    fun encodeRemainingLength(length: Int): ByteArray {
        require(length in 0..MAX_REMAINING_LENGTH) { "remaining length out of range: $length" }
        val out = ByteArrayOutputStream(4)
        var x = length
        do {
            var digit = x % 128
            x /= 128
            if (x > 0) digit = digit or 0x80
            out.write(digit)
        } while (x > 0)
        return out.toByteArray()
    }

    /** Reads the varint after the fixed-header byte; more than 4 bytes = malformed (2.2.3). */
    fun readRemainingLength(input: InputStream): Int {
        var multiplier = 1
        var value = 0
        repeat(4) {
            val b = input.read()
            if (b < 0) throw EOFException("stream ended inside the remaining length")
            value += (b and 0x7F) * multiplier
            if (b and 0x80 == 0) return value
            multiplier *= 128
        }
        throw MqttProtocolException("remaining length longer than 4 bytes")
    }

    fun utf8(text: String): ByteArray = binary(text.encodeToByteArray())

    fun binary(bytes: ByteArray): ByteArray {
        require(bytes.size <= 0xFFFF) { "string longer than 65535 bytes" }
        return byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes
    }

    private fun packet(firstByte: Int, body: ByteArray): ByteArray = byteArrayOf(firstByte.toByte()) + encodeRemainingLength(body.size) + body

    /** 3.1: protocol name «MQTT», level 4, flags, keep alive, then client id, will topic/message, user name, password. */
    fun connect(
        clientId: String,
        keepAliveSec: Int,
        cleanSession: Boolean = true,
        username: String? = null,
        password: String? = null,
        will: MqttMessage? = null,
    ): ByteArray {
        require(keepAliveSec in 0..0xFFFF)
        // 3.1.2.9: a password without a user name is not allowed in 3.1.1.
        require(password == null || username != null) { "password requires a user name" }
        var flags = 0
        if (cleanSession) flags = flags or 0x02
        if (will != null) {
            flags = flags or 0x04 or (will.qos.value shl 3)
            if (will.retain) flags = flags or 0x20
        }
        if (password != null) flags = flags or 0x40
        if (username != null) flags = flags or 0x80
        val body = ByteArrayOutputStream()
        body.write(utf8("MQTT"))
        body.write(4)
        body.write(flags)
        body.write(keepAliveSec shr 8)
        body.write(keepAliveSec and 0xFF)
        body.write(utf8(clientId))
        if (will != null) {
            body.write(utf8(will.topic))
            body.write(binary(will.payload))
        }
        if (username != null) body.write(utf8(username))
        if (password != null) body.write(binary(password.encodeToByteArray()))
        return packet(CONNECT shl 4, body.toByteArray())
    }

    /** 3.3: topic, packet id for QoS 1, payload; DUP only for a re-sent QoS 1 message. */
    fun publish(message: MqttMessage, packetId: Int = 0, dup: Boolean = false): ByteArray {
        require(message.topic.isNotEmpty() && '+' !in message.topic && '#' !in message.topic) { "invalid topic name" }
        var first = PUBLISH shl 4 or (message.qos.value shl 1)
        if (message.retain) first = first or 0x01
        if (dup && message.qos != MqttQos.AT_MOST_ONCE) first = first or 0x08
        val body = ByteArrayOutputStream()
        body.write(utf8(message.topic))
        if (message.qos != MqttQos.AT_MOST_ONCE) {
            require(packetId in 1..0xFFFF) { "QoS 1 needs a packet id 1..65535" }
            body.write(packetId shr 8)
            body.write(packetId and 0xFF)
        }
        body.write(message.payload)
        return packet(first, body.toByteArray())
    }

    val PINGREQ_PACKET: ByteArray = byteArrayOf(0xC0.toByte(), 0x00)
    val DISCONNECT_PACKET: ByteArray = byteArrayOf(0xE0.toByte(), 0x00)

    /** One packet off the wire: fixed header byte and the body (variable header + payload). */
    class Packet(val firstByte: Int, val body: ByteArray) {
        val type: Int get() = firstByte ushr 4
        val flags: Int get() = firstByte and 0x0F
    }

    /** Blocking read of one packet; EOF before the first byte → [EOFException]. */
    fun readPacket(input: InputStream, maxBody: Int = 64 * 1024): Packet {
        val first = input.read()
        if (first < 0) throw EOFException("connection closed by the broker")
        val length = readRemainingLength(input)
        if (length > maxBody) throw MqttProtocolException("packet too large: $length")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw EOFException("stream ended inside a packet")
            read += n
        }
        return Packet(first, body)
    }

    /** 3.2: CONNACK = 0x20, length 2, ack flags (only bit 0 «session present»), return code 0..5. */
    fun parseConnack(packet: Packet): ConnackCode {
        if (packet.type != CONNACK || packet.flags != 0) throw MqttProtocolException("expected CONNACK, got type ${packet.type}")
        if (packet.body.size != 2) throw MqttProtocolException("CONNACK length ${packet.body.size}, expected 2")
        val ackFlags = packet.body[0].toInt() and 0xFF
        if (ackFlags and 0xFE != 0) throw MqttProtocolException("CONNACK reserved flags set")
        val code = packet.body[1].toInt() and 0xFF
        return ConnackCode.entries.firstOrNull { it.code == code } ?: throw MqttProtocolException("unknown CONNACK return code $code")
    }

    /** 3.4: PUBACK = 0x40, length 2, packet id. */
    fun parsePuback(packet: Packet): Int {
        if (packet.type != PUBACK || packet.body.size != 2) throw MqttProtocolException("malformed PUBACK")
        return (packet.body[0].toInt() and 0xFF shl 8) or (packet.body[1].toInt() and 0xFF)
    }
}

/** 3.2.2.3 CONNACK return codes. */
enum class ConnackCode(val code: Int) {
    ACCEPTED(0),
    UNACCEPTABLE_PROTOCOL_VERSION(1),
    IDENTIFIER_REJECTED(2),
    SERVER_UNAVAILABLE(3),
    BAD_USERNAME_OR_PASSWORD(4),
    NOT_AUTHORIZED(5),
}

enum class MqttQos(val value: Int) { AT_MOST_ONCE(0), AT_LEAST_ONCE(1) }

class MqttMessage(val topic: String, val payload: ByteArray, val qos: MqttQos = MqttQos.AT_MOST_ONCE, val retain: Boolean = false) {
    constructor(topic: String, text: String, qos: MqttQos = MqttQos.AT_MOST_ONCE, retain: Boolean = false) :
        this(topic, text.encodeToByteArray(), qos, retain)

    override fun toString() = "MqttMessage($topic, ${payload.size} B, qos=${qos.value}, retain=$retain)"
}

/** The broker sent something that is not valid MQTT 3.1.1 for this client. */
class MqttProtocolException(message: String) : IOException(message)

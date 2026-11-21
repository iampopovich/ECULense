package io.github.iampopovich.eculense.core.mqtt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

/** Byte-level checks against the MQTT 3.1.1 standard (OASIS, mqtt-v3.1.1-os). */
class MqttCodecTest {
    private fun hex(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `remaining length uses the 1 to 4 byte varint of table 2_4`() {
        val cases = mapOf(
            0 to hex(0x00), 127 to hex(0x7F), 128 to hex(0x80, 0x01), 16_383 to hex(0xFF, 0x7F),
            16_384 to hex(0x80, 0x80, 0x01), 2_097_151 to hex(0xFF, 0xFF, 0x7F), 2_097_152 to hex(0x80, 0x80, 0x80, 0x01),
            268_435_455 to hex(0xFF, 0xFF, 0xFF, 0x7F),
        )
        for ((n, bytes) in cases) {
            assertArrayEquals("$n", bytes, MqttCodec.encodeRemainingLength(n))
            assertEquals(n, MqttCodec.readRemainingLength(ByteArrayInputStream(bytes)))
        }
        assertThrows(IllegalArgumentException::class.java) { MqttCodec.encodeRemainingLength(268_435_456) }
        assertThrows(MqttProtocolException::class.java) { MqttCodec.readRemainingLength(ByteArrayInputStream(hex(0x80, 0x80, 0x80, 0x80, 0x01))) }
    }

    @Test
    fun `utf-8 strings carry a 2 byte length (1_5_3, example U+2A6D4 and U+FEFF)`() {
        assertArrayEquals(hex(0x00, 0x05, 0x41, 0xF0, 0xAA, 0x9B, 0x94), MqttCodec.utf8("A\uD869\uDED4"))
        assertArrayEquals(hex(0x00, 0x03, 0xC2, 0xB0, 0x43), MqttCodec.utf8("°C"))
    }

    @Test
    fun `CONNECT variable header matches figure 3_6 (user, password, will QoS 1, clean session, keep alive 10)`() {
        val will = MqttMessage("s/status", "offline", MqttQos.AT_LEAST_ONCE, retain = false)
        val p = MqttCodec.connect("c1", 10, cleanSession = true, username = "u", password = "p", will = will)
        assertEquals(0x10, p[0].toInt())
        val body = p.copyOfRange(2, p.size)
        assertArrayEquals(hex(0x00, 0x04, 'M'.code, 'Q'.code, 'T'.code, 'T'.code, 0x04, 0xCE, 0x00, 0x0A), body.copyOfRange(0, 10))
        // payload order: client id, will topic, will message, user name, password
        val payload = body.copyOfRange(10, body.size)
        val expected = MqttCodec.utf8("c1") + MqttCodec.utf8("s/status") + MqttCodec.binary("offline".encodeToByteArray()) +
            MqttCodec.utf8("u") + MqttCodec.binary("p".encodeToByteArray())
        assertArrayEquals(expected, payload)
        assertEquals(body.size, p[1].toInt())
    }

    @Test
    fun `retained will sets bit 5, no will and no credentials leave only clean session`() {
        val retained = MqttCodec.connect("c", 60, will = MqttMessage("t", "x", MqttQos.AT_LEAST_ONCE, retain = true))
        assertEquals(0x2E, retained[2 + 7].toInt() and 0xFF)
        val bare = MqttCodec.connect("c", 60)
        assertEquals(0x02, bare[2 + 7].toInt() and 0xFF)
        assertThrows(IllegalArgumentException::class.java) { MqttCodec.connect("c", 60, password = "p") }
    }

    @Test
    fun `PUBLISH header flags and packet id (3_3)`() {
        val q0 = MqttCodec.publish(MqttMessage("a/b", "1"))
        assertArrayEquals(hex(0x30, 0x06, 0x00, 0x03, 'a'.code, '/'.code, 'b'.code, '1'.code), q0)
        val q1 = MqttCodec.publish(MqttMessage("a/b", "1", MqttQos.AT_LEAST_ONCE, retain = true), packetId = 10)
        assertArrayEquals(hex(0x33, 0x08, 0x00, 0x03, 'a'.code, '/'.code, 'b'.code, 0x00, 0x0A, '1'.code), q1)
        val dup = MqttCodec.publish(MqttMessage("a/b", "1", MqttQos.AT_LEAST_ONCE), packetId = 10, dup = true)
        assertEquals(0x3A, dup[0].toInt() and 0xFF)
        assertThrows(IllegalArgumentException::class.java) { MqttCodec.publish(MqttMessage("a/+", "1")) }
        assertThrows(IllegalArgumentException::class.java) { MqttCodec.publish(MqttMessage("a", "1", MqttQos.AT_LEAST_ONCE)) }
    }

    @Test
    fun `fixed packets PINGREQ and DISCONNECT`() {
        assertArrayEquals(hex(0xC0, 0x00), MqttCodec.PINGREQ_PACKET)
        assertArrayEquals(hex(0xE0, 0x00), MqttCodec.DISCONNECT_PACKET)
    }

    private fun packet(vararg b: Int) = MqttCodec.readPacket(ByteArrayInputStream(hex(*b)))

    @Test
    fun `CONNACK codes are typed, malformed ones throw`() {
        assertEquals(ConnackCode.ACCEPTED, MqttCodec.parseConnack(packet(0x20, 0x02, 0x00, 0x00)))
        assertEquals(ConnackCode.ACCEPTED, MqttCodec.parseConnack(packet(0x20, 0x02, 0x01, 0x00))) // session present
        assertEquals(ConnackCode.BAD_USERNAME_OR_PASSWORD, MqttCodec.parseConnack(packet(0x20, 0x02, 0x00, 0x04)))
        assertEquals(ConnackCode.NOT_AUTHORIZED, MqttCodec.parseConnack(packet(0x20, 0x02, 0x00, 0x05)))
        assertThrows(MqttProtocolException::class.java) { MqttCodec.parseConnack(packet(0x20, 0x02, 0x00, 0x06)) }
        assertThrows(MqttProtocolException::class.java) { MqttCodec.parseConnack(packet(0x20, 0x03, 0x00, 0x00, 0x00)) }
        assertThrows(MqttProtocolException::class.java) { MqttCodec.parseConnack(packet(0x20, 0x02, 0x02, 0x00)) }
        assertThrows(MqttProtocolException::class.java) { MqttCodec.parseConnack(packet(0x30, 0x02, 0x00, 0x00)) }
        assertEquals(10, MqttCodec.parsePuback(packet(0x40, 0x02, 0x00, 0x0A)))
    }
}

package io.github.iampopovich.eculense.core.mqtt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/** The own MQTT client against [FakeBroker] on a loopback socket (real sockets, short timings). */
class MqttClientTest {
    private val broker = FakeBroker()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = MqttClient(scope)

    @After fun tearDown() {
        runBlocking { client.stop() }
        scope.cancel()
        broker.close()
    }

    private fun config(
        keepAliveSec: Int = 60,
        user: String? = "test-user",
        password: String? = "test-pass",
        ackTimeoutMs: Long = 2_000,
        maxRetries: Int = 2,
        port: Int = broker.port,
        tls: Boolean = false,
        host: String = "127.0.0.1",
    ) = MqttConfig(
        host = host, port = port, tls = tls, clientId = "eculenseTest01", username = user, password = password,
        keepAliveSec = keepAliveSec, will = HaDiscovery.offlineMessage("eculense"), connectTimeoutMs = 2_000,
        ackTimeoutMs = ackTimeoutMs, maxRetries = maxRetries, backoffInitialMs = 100, backoffMaxMs = 400,
    )

    private fun awaitState(predicate: (MqttState) -> Boolean): MqttState = runBlocking { withTimeout(5_000) { client.state.first(predicate) } }

    @Test
    fun `CONNECT carries clean session, credentials, keep alive and the retained LWT, then QoS 0 and QoS 1 publish`() {
        client.start(config()) { listOf(HaDiscovery.onlineMessage("eculense")) }
        awaitState { it == MqttState.Connected }
        val c = broker.connects.single()
        assertEquals("MQTT", c.protocol)
        assertEquals(4, c.level)
        assertTrue(c.cleanSession)
        assertEquals(60, c.keepAliveSec)
        assertEquals("eculenseTest01", c.clientId)
        assertEquals("eculense/status", c.willTopic)
        assertEquals("offline", c.willPayload)
        assertEquals(1, c.willQos)
        assertTrue(c.willRetain)
        assertEquals("test-user", c.username)
        assertEquals("test-pass", c.password)

        client.publish(MqttMessage("eculense/n/state", "{\"pid_0c\":800}"))
        client.publish(MqttMessage("homeassistant/device/n/config", "{}", MqttQos.AT_LEAST_ONCE, retain = true))
        val p = broker.awaitPublishes(3)
        assertEquals(listOf("eculense/status", "eculense/n/state", "homeassistant/device/n/config"), p.map { it.topic })
        assertEquals(listOf(1, 0, 1), p.map { it.qos })
        assertEquals(listOf(true, false, true), p.map { it.retain })
        assertEquals(null, p[1].packetId)
        assertNotNull(p[2].packetId)
        assertTrue(broker.await { client.queued().isEmpty() })
        assertEquals("online", broker.retained["eculense/status"])
    }

    @Test
    fun `probe types every CONNACK refusal and a malformed CONNACK`() = runBlocking {
        broker.connackBytes = byteArrayOf(0x20, 0x02, 0x00, 0x04)
        val bad = client.probe(config())
        assertEquals(ConnectResult.Refused(ConnackCode.BAD_USERNAME_OR_PASSWORD), bad)
        assertTrue(bad.badCredentials)
        broker.connackBytes = byteArrayOf(0x20, 0x02, 0x00, 0x05)
        assertTrue(client.probe(config()).badCredentials)
        broker.connackBytes = byteArrayOf(0x20, 0x02, 0x00, 0x03)
        val unavailable = client.probe(config())
        assertEquals(ConnectResult.Refused(ConnackCode.SERVER_UNAVAILABLE), unavailable)
        assertFalse(unavailable.fatal)
        broker.connackBytes = byteArrayOf(0x20, 0x03, 0x00, 0x00, 0x00)
        assertTrue(client.probe(config()) is ConnectResult.Protocol)
        broker.connackBytes = byteArrayOf(0x20, 0x02, 0x00, 0x00)
        assertEquals(ConnectResult.Accepted, client.probe(config()))
        assertTrue(broker.await { broker.disconnects.get() == 1 })
    }

    @Test
    fun `closed port is unreachable, plain TCP to an internet host is refused before connecting, TLS to a plain broker fails as TLS`() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        assertTrue(client.probe(config(port = closedPort)) is ConnectResult.Unreachable)
        val never = MqttClient(scope, connector = { _, _, _, _ -> error("must not connect") })
        assertEquals(ConnectResult.PlainTextNotAllowed, never.probe(config(host = "203.0.113.5")))
        assertTrue(client.probe(config(tls = true, host = "localhost")) is ConnectResult.TlsFailed)
    }

    @Test
    fun `bad credentials stop the client without retrying`() {
        broker.connackBytes = byteArrayOf(0x20, 0x02, 0x00, 0x04)
        client.start(config())
        val s = awaitState { it is MqttState.Failed } as MqttState.Failed
        assertTrue(s.error.badCredentials)
        Thread.sleep(500)
        assertEquals(1, broker.accepted.get())
    }

    @Test
    fun `keep alive sends PINGREQ about once per period and reconnects when PINGRESP never comes`() {
        client.start(config(keepAliveSec = 1))
        awaitState { it == MqttState.Connected }
        assertTrue(broker.await(4_000) { broker.pingTimes.size >= 2 })
        val gap = broker.pingTimes[1] - broker.pingTimes[0]
        assertTrue("ping gap $gap ms", gap in 800..1_600)
        broker.answerPings = false
        assertTrue(broker.await(6_000) { broker.accepted.get() >= 2 })
    }

    @Test
    fun `reconnects after the broker drops the link, birth messages are sent again, queued QoS 1 survives`() {
        client.start(config()) { listOf(HaDiscovery.onlineMessage("eculense")) }
        awaitState { it == MqttState.Connected }
        broker.awaitPublishes(1)
        broker.dropClients()
        assertTrue(broker.await { broker.accepted.get() == 2 && broker.publishes.size >= 2 })
        awaitState { it == MqttState.Connected }
        client.publish(MqttMessage("eculense/n/state", "after"))
        val p = broker.awaitPublishes(3)
        assertEquals(listOf("eculense/status", "eculense/status", "eculense/n/state"), p.map { it.topic })
    }

    @Test
    fun `QoS 1 without PUBACK is re-sent with DUP, then on a new link, and delivered once acknowledged`() {
        broker.ackQos1 = false
        client.start(config(ackTimeoutMs = 300, maxRetries = 1))
        awaitState { it == MqttState.Connected }
        client.publish(MqttMessage("homeassistant/device/n/config", "{}", MqttQos.AT_LEAST_ONCE, retain = true))
        assertTrue(broker.await { broker.publishes.size >= 2 })
        val (first, second) = broker.publishes.take(2)
        assertEquals(first.packetId, second.packetId)
        assertFalse(first.dup)
        assertTrue(second.dup)
        assertEquals(1, client.queued().size)
        // no PUBACK after the retries: the link is reopened and the message goes again
        assertTrue(broker.await { broker.accepted.get() >= 2 })
        broker.ackQos1 = true
        assertTrue(broker.await { client.queued().isEmpty() })
    }

    @Test
    fun `stop publishes the final offline message and sends DISCONNECT`() = runBlocking {
        client.start(config()) { listOf(HaDiscovery.onlineMessage("eculense")) }
        awaitState { it == MqttState.Connected }
        broker.awaitPublishes(1)
        client.stop(HaDiscovery.offlineMessage("eculense"))
        assertTrue(broker.await { broker.disconnects.get() == 1 })
        assertEquals("offline", broker.retained["eculense/status"])
        assertEquals(MqttState.Idle, client.state.value)
    }
}

package io.github.iampopovich.eculense.feature.mqtt

import io.github.iampopovich.eculense.core.data.InMemoryDashboardPrefs
import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.mqtt.FakeBroker
import io.github.iampopovich.eculense.core.mqtt.MqttClient
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Switch-off end to end: [MqttBridge] + the real [MqttClient] against the loopback [FakeBroker] (real sockets). */
class MqttSwitchOffBrokerTest {
    private val broker = FakeBroker()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val car = CarProfile(id = 3, name = "Тестовая", brand = "Kia", model = "Ceed", year = 2019)
    private val real = ConnectionState.Connected(AdapterEndpoint("bt", "00:11:22:33:44:55", "OBDII"), AdapterInfo("ELM327 v1.5", '6', true, 12.6), setOf(0x0C))
    private val configTopic = "homeassistant/device/eculense_a1b2c3d4e5f6_c3/config"

    @After fun tearDown() {
        scope.cancel()
        broker.close()
    }

    private fun bridge(state: ConnectionState, settings: InMemoryMqttSettings): MqttBridge = MqttBridge(
        FakeConnectionController(initial = state), settings, InMemoryDashboardPrefs(selection = listOf(0x0C)), flowOf(car), { flowOf(0) },
        flowOf(emptyList()), "0.2.0-test", MqttClient(scope), scope, ticks = { MutableSharedFlow() },
    ).also { it.start() }

    private fun on(port: Int) = MqttSettings(enabled = true, host = "127.0.0.1", port = port, prefix = "eculense")

    @Test
    fun `switch-off clears the retained discovery config on the broker and leaves offline`() = runBlocking {
        val settings = InMemoryMqttSettings(on(broker.port))
        val b = bridge(real, settings)
        assertTrue("discovery announced", broker.await { broker.retained.containsKey(configTopic) })
        assertEquals("online", broker.retained["eculense/status"])

        settings.update { it.copy(enabled = false) }
        assertTrue("device removed", broker.await { !broker.retained.containsKey(configTopic) && broker.disconnects.get() >= 2 })
        assertEquals("offline", broker.retained["eculense/status"])
        val removal = broker.publishes.last { it.topic == configTopic }
        assertEquals("", removal.payload)
        assertTrue(removal.retain)
        assertEquals(listOf("eculensea1b2c3d4e5f6", "eculensea1b2c3d4e5f6r"), broker.connects.map { it.clientId })
        withTimeout(5_000) { b.status.first { it == BridgeStatus.Off } }
        Unit
    }

    @Test
    fun `switch-off without an adapter connects once, removes and disconnects`() = runBlocking {
        broker.retained[configTopic] = "{\"dev\":{}}" // left by an earlier session
        val settings = InMemoryMqttSettings(on(broker.port))
        bridge(ConnectionState.Disconnected, settings)
        Thread.sleep(200)
        assertTrue(broker.connects.isEmpty()) // waiting for an adapter publishes nothing
        settings.update { it.copy(enabled = false) }
        assertTrue(broker.await { broker.disconnects.get() == 1 })
        assertFalse(broker.retained.containsKey(configTopic))
        assertEquals("offline", broker.retained["eculense/status"])
        assertEquals(1, broker.connects.size)
    }
}

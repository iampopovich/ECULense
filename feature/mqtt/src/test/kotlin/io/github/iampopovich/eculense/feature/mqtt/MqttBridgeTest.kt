package io.github.iampopovich.eculense.feature.mqtt

import io.github.iampopovich.eculense.core.data.InMemoryDashboardPrefs
import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.mqtt.ConnectResult
import io.github.iampopovich.eculense.core.mqtt.MqttConfig
import io.github.iampopovich.eculense.core.mqtt.MqttMessage
import io.github.iampopovich.eculense.core.mqtt.MqttPublisher
import io.github.iampopovich.eculense.core.mqtt.MqttState
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePublisher : MqttPublisher {
    override val state = MutableStateFlow<MqttState>(MqttState.Idle)
    val starts = mutableListOf<Pair<MqttConfig, List<MqttMessage>>>()
    val published = mutableListOf<MqttMessage>()
    val stops = mutableListOf<MqttMessage?>()
    override fun start(config: MqttConfig, birth: () -> List<MqttMessage>) {
        starts += config to birth()
        state.value = MqttState.Connected
    }
    override fun publish(message: MqttMessage) { published += message }
    override suspend fun stop(final: MqttMessage?) { stops += final; state.value = MqttState.Idle }
    override suspend fun probe(config: MqttConfig): ConnectResult = ConnectResult.Accepted
    val once = mutableListOf<Pair<MqttConfig, List<MqttMessage>>>()
    var onceResult: ConnectResult = ConnectResult.Accepted
    override suspend fun sendOnce(config: MqttConfig, messages: List<MqttMessage>): ConnectResult {
        once += config to messages
        return onceResult
    }
    fun allPayloads(): List<String> = (starts.flatMap { it.second } + published + stops.filterNotNull()).map { it.payload.decodeToString() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MqttBridgeTest {
    private val car = CarProfile(id = 3, name = "Тестовая", brand = "Kia", model = "Ceed", year = 2019, vin = "TESTVIN0000000001")
    private val real = ConnectionState.Connected(AdapterEndpoint("bt", "00:11:22:33:44:55", "OBDII"), AdapterInfo("ELM327 v1.5", '6', true, 12.6), setOf(0x0C, 0x0D, 0x05, 0x42))
    private val demo = ConnectionState.Connected(AdapterEndpoint("demo", "demo", "Demo"), AdapterInfo("ELM327 v2.1", '6', true, 12.6), setOf(0x0C, 0x0D))
    private val enabled = MqttSettings(enabled = true, host = "192.168.1.20", username = "ha", prefix = "eculense")

    private class Rig(
        val controller: FakeConnectionController,
        val settings: InMemoryMqttSettings,
        val publisher: FakePublisher,
        val ticks: MutableSharedFlow<Unit>,
        val bridge: MqttBridge,
    )

    private fun TestScope.rig(state: ConnectionState, s: MqttSettings = enabled, dtc: Int? = 2): Rig {
        val controller = FakeConnectionController(initial = state)
        val settings = InMemoryMqttSettings(s, storedPassword = "test-pass")
        val publisher = FakePublisher()
        val ticks = MutableSharedFlow<Unit>()
        val bridge = MqttBridge(
            controller, settings, InMemoryDashboardPrefs(selection = listOf(0x0C, 0x05, 0x10)), flowOf(car), { flowOf(dtc) }, flowOf(emptyList()),
            "0.2.0-test", publisher, backgroundScope, ticks = { ticks },
        )
        bridge.start()
        return Rig(controller, settings, publisher, ticks, bridge)
    }

    @Test
    fun `a real adapter starts publishing with LWT, discovery and the dashboard PIDs, no personal data`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(real)
        runCurrent()
        val (config, birth) = r.publisher.starts.single()
        assertEquals("192.168.1.20", config.host)
        assertEquals("eculensea1b2c3d4e5f6", config.clientId)
        assertEquals("ha", config.username)
        assertEquals("test-pass", config.password)
        assertEquals("eculense/status", config.will!!.topic)
        assertEquals("offline", config.will!!.payload.decodeToString())
        assertTrue(config.will!!.retain)
        assertEquals(listOf("eculense/status", "homeassistant/device/eculense_a1b2c3d4e5f6_c3/config"), birth.map { it.topic })
        val discovery = birth[1].payload.decodeToString()
        assertTrue(discovery, "\"name\":\"Kia Ceed 2019\"" in discovery)
        assertTrue("pid_0c" in discovery && "pid_05" in discovery && "battery_voltage" in discovery && "dtc_count" in discovery)
        assertFalse("pid_10" in discovery) // MAF is not supported by this car
        // owner «mqtt» polls the resolved dashboard selection + module voltage for the battery
        assertEquals(listOf(0x0C, 0x05, 0x42), r.controller.polledBy.value.getValue(MqttBridge.OWNER).map { it.code })

        r.controller.readingsFlow.emit(SensorReading("01.0C", 812.346, "rpm", 0))
        r.controller.readingsFlow.emit(SensorReading("elm.voltage", 13.91, "V", 0))
        r.ticks.emit(Unit)
        val state = r.publisher.published.single()
        assertEquals("eculense/eculense_a1b2c3d4e5f6_c3/state", state.topic)
        assertFalse(state.retain)
        assertEquals("{\"pid_0c\":812.35,\"battery_voltage\":13.91,\"connection\":\"connected\",\"dtc_count\":2}", state.payload.decodeToString())

        val everything = r.publisher.allPayloads().joinToString("\n") + config.toString()
        for (secret in listOf("Тестовая", "TESTVIN", "00:11:22:33:44:55", "OBDII", "test-pass")) assertFalse(secret, secret in everything)
    }

    @Test
    fun `simulated links publish nothing`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(demo)
        runCurrent()
        assertTrue(r.publisher.starts.isEmpty())
        assertEquals(BridgeStatus.SimulatedLink, r.bridge.status.value)
        assertNull(r.controller.polledBy.value[MqttBridge.OWNER])
    }

    @Test
    fun `off by default and without an adapter nothing starts`() = runTest(UnconfinedTestDispatcher()) {
        val off = rig(real, s = MqttSettings())
        runCurrent()
        assertTrue(off.publisher.starts.isEmpty())
        assertEquals(BridgeStatus.Off, off.bridge.status.value)
        val waiting = rig(ConnectionState.Disconnected)
        runCurrent()
        assertTrue(waiting.publisher.starts.isEmpty())
        assertEquals(BridgeStatus.WaitingForAdapter, waiting.bridge.status.value)
    }

    @Test
    fun `disconnect or switching off sends offline and stops polling`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(real)
        runCurrent()
        r.controller.stateFlow.value = ConnectionState.Reconnecting(real.endpoint, 1, 1_000, io.github.iampopovich.eculense.core.obd.ObdError.AdapterClosed)
        runCurrent()
        assertEquals(1, r.publisher.starts.size) // reconnecting keeps the session
        r.controller.stateFlow.value = ConnectionState.Disconnected
        runCurrent()
        assertEquals("offline", r.publisher.stops.single()!!.payload.decodeToString())
        assertNull(r.controller.polledBy.value[MqttBridge.OWNER])
        assertEquals(BridgeStatus.WaitingForAdapter, r.bridge.status.value)

        r.controller.stateFlow.value = real
        runCurrent()
        assertEquals(2, r.publisher.starts.size)
        r.settings.update { it.copy(enabled = false) }
        runCurrent()
        assertEquals(2, r.publisher.stops.size)
        assertEquals(BridgeStatus.Off, r.bridge.status.value)
    }

    @Test
    fun `switching off removes the HA device with an empty retained config and leaves offline`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(real)
        runCurrent()
        r.settings.update { it.copy(enabled = false) }
        runCurrent()
        assertEquals("offline", r.publisher.stops.single()!!.payload.decodeToString()) // the running session first
        val (config, messages) = r.publisher.once.single()
        assertEquals("192.168.1.20", config.host)
        assertEquals("eculensea1b2c3d4e5f6r", config.clientId) // own id, not the main one
        assertEquals("ha", config.username)
        assertEquals("test-pass", config.password)
        assertNull(config.will)
        assertEquals(listOf("homeassistant/device/eculense_a1b2c3d4e5f6_c3/config", "eculense/status"), messages.map { it.topic })
        assertEquals(0, messages[0].payload.size)
        assertTrue(messages.all { it.retain })
        assertEquals("offline", messages[1].payload.decodeToString())
        assertEquals(BridgeStatus.Off, r.bridge.status.value)

        // later edits while off (and the same switch-off seen again) do not repeat the removal
        r.settings.update { it.copy(periodSec = 30) }
        r.controller.stateFlow.value = ConnectionState.Disconnected
        runCurrent()
        assertEquals(1, r.publisher.once.size)
    }

    @Test
    fun `switching off without an adapter still removes the device, unreachable broker is only logged`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig(ConnectionState.Disconnected)
        runCurrent()
        assertTrue(r.publisher.starts.isEmpty())
        r.publisher.onceResult = ConnectResult.Unreachable("test")
        r.settings.update { it.copy(enabled = false) }
        runCurrent()
        assertTrue(r.publisher.stops.isEmpty()) // nothing was running
        assertEquals("homeassistant/device/eculense_a1b2c3d4e5f6_c3/config", r.publisher.once.single().second.first().topic)
        assertEquals(BridgeStatus.Off, r.bridge.status.value)
        // on again and off again = a second removal
        r.settings.update { it.copy(enabled = true) }
        runCurrent()
        r.settings.update { it.copy(enabled = false) }
        runCurrent()
        assertEquals(2, r.publisher.once.size)
    }

    @Test
    fun `off from the start or a blank host never contacts the broker`() = runTest(UnconfinedTestDispatcher()) {
        val off = rig(real, s = enabled.copy(enabled = false))
        runCurrent()
        off.settings.update { it.copy(host = "192.168.1.21") }
        runCurrent()
        assertTrue(off.publisher.once.isEmpty())
        val blank = rig(real, s = enabled.copy(host = ""))
        runCurrent()
        blank.settings.update { it.copy(enabled = false) }
        runCurrent()
        assertTrue(blank.publisher.once.isEmpty())
    }
}

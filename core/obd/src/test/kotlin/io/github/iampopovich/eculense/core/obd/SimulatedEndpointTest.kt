package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.AdapterInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedEndpointTest {
    @Test
    fun `demo kinds, replay and the pc emulator are simulated`() {
        listOf("demo", "demo-flaky", "demo-engine-off", "replay").forEach {
            assertTrue(it, AdapterEndpoint(it, "x", "x").isSimulated)
        }
        assertTrue(AdapterEndpoint("wifi", "10.0.2.2:35000", "emu", EndpointLabel.PC_EMULATOR).isSimulated)
    }

    @Test
    fun `real transports are not simulated`() {
        listOf("bt", "wifi", "usb").forEach { assertFalse(it, AdapterEndpoint(it, "x", "Demo OBD").isSimulated) }
    }

    @Test
    fun `state carries the flag of its endpoint`() {
        val demo = AdapterEndpoint("demo", "x", "x")
        assertTrue(ConnectionState.Connected(demo, AdapterInfo("v", '6', true, null), emptySet()).isSimulated)
        assertTrue(ConnectionState.EngineOff(demo, 12.0).isSimulated)
        assertFalse(ConnectionState.Disconnected.isSimulated)
    }
}

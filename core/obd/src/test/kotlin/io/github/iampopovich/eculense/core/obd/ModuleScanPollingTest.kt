package io.github.iampopovich.eculense.core.obd

import io.github.iampopovich.eculense.core.model.SensorReading
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-D: live polling pauses while a module scan holds the headers on and resumes with plain values afterwards. */
class ModuleScanPollingTest {
    private val endpoint = AdapterEndpoint("fake", "00:11", "Fake ELM")

    @Test
    fun `no live request is sent between ATH1 and ATH0 and polling resumes after the scan`() = runTest {
        val adapter = MultiEcuFakeAdapter(
            headered = mapOf("7DF|0100" to "7E8064100BE3FA813", "7D1|3E00" to "7D9027E00"),
            latencyMs = 20,
        )
        val factory = object : TransportFactory {
            override val kind = "fake"
            override suspend fun discover() = listOf(endpoint)
            override fun create(endpoint: AdapterEndpoint): ObdTransport = adapter
        }
        val manager = ObdConnectionManager(listOf(factory), backgroundScope, clock = { testScheduler.currentTime })
        manager.connect(endpoint)
        manager.state.first { it is ConnectionState.Connected }
        val readings = mutableListOf<SensorReading>()
        backgroundScope.launch { manager.readings.collect { readings += it } }
        manager.setPolledPids(listOf(StandardPids.byCode(0x0C)!!))
        manager.readings.first()

        val result = manager.withExclusiveSession { ModuleScanner().scan(it) }!!
        assertEquals(listOf("7E8", "7D9"), result.modules.map { it.module.address.rx })
        val on = adapter.sent.indexOf("ATH1")
        val off = adapter.sent.lastIndexOf("ATH0")
        assertTrue(on in 0 until off)
        assertTrue(adapter.sent.subList(on, off).none { it.startsWith("010C") })

        val next = manager.readings.first { it.sensorId == "01.0C" }
        assertEquals(1726.0, next.value, 0.001) // a plain ATH0 value: the headers were restored
        assertTrue(adapter.sent.drop(off).any { it.startsWith("010C") })
        assertTrue(readings.isNotEmpty())
        manager.disconnect()
    }
}

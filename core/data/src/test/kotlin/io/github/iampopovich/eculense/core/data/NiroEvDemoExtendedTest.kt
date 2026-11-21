package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.demo.NiroEvDemoTransportFactory
import io.github.iampopovich.eculense.core.demo.ObdbFixtures
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.ObdConnectionManager
import io.github.iampopovich.eculense.core.obd.ObdTrafficLog
import io.github.iampopovich.eculense.core.obd.StandardPids
import io.github.iampopovich.eculense.core.obd.isSimulated
import io.github.iampopovich.eculense.core.obdb.SignalsetParser
import io.github.iampopovich.eculense.core.obdb.SimulatedSignalset
import io.github.iampopovich.eculense.core.obdb.StoredSignalset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-E end to end without hardware: the debug «Demo: Niro EV (OBDb)» adapter answers the planned extended requests with
 * the OBDb Kia-Niro-EV 2020 test-case frames; the decoded values equal OBDb's expected values, J1979 PIDs keep coming
 * between the extended groups, and nothing about the link is a user car.
 */
class NiroEvDemoExtendedTest {
    @Test
    fun `demo Niro EV - SOC, HV voltage and SOH match the test case, J1979 polling continues`() = runTest {
        val clock = { testScheduler.currentTime }
        val log = io.github.iampopovich.eculense.core.obd.RingTrafficLog(capacity = 200_000, clock = clock)
        val manager = ObdConnectionManager(listOf(NiroEvDemoTransportFactory(clock = clock, latencyMs = { 5 })), backgroundScope, trafficLog = log, clock = clock)
        val niro = StoredSignalset("OBDb/Kia-Niro-EV", "demo", SignalsetParser.parse(ObdbFixtures.signalset(ObdbFixtures.NIRO_EV)))
        val sim = object : SimulatedSignalset {
            override val endpointKind = NiroEvDemoTransportFactory.KIND
            override val year = NiroEvDemoTransportFactory.YEAR
            override fun signalset() = niro
        }
        val prefs = object : ExtendedSensorsPrefs {
            override val enabled = MutableStateFlow(true)
            override suspend fun setEnabled(on: Boolean) { enabled.value = on }
        }
        val sensors = ExtendedSensors(manager, MutableStateFlow<CarProfile?>(null), { _, _ -> null }, prefs, listOf(sim), backgroundScope)
        sensors.start()
        manager.connect(NiroEvDemoTransportFactory.ENDPOINT)
        manager.state.first { it is ConnectionState.Connected }
        assertTrue(manager.state.value.isSimulated)
        val readings = mutableListOf<SensorReading>()
        backgroundScope.launch { manager.readings.collect { readings += it } }
        manager.setPolledPids(listOf(StandardPids.byCode(0x0C)!!, StandardPids.byCode(0x0D)!!))

        val soc = manager.readings.first { it.sensorId == "obdb:NIROEV_HVBAT_SOC" }
        val expected = ObdbFixtures.testCases(ObdbFixtures.NIRO_EV).first { it.request == "220101" }.cases
        assertTrue(soc.value in expected.map { it.expected.getValue("NIROEV_HVBAT_SOC").toDouble() })
        val soh = manager.readings.first { it.sensorId == "obdb:NIROEV_HVBAT_SOH" }
        val sohExpected = ObdbFixtures.testCases(ObdbFixtures.NIRO_EV).first { it.request == "220105" }.cases.mapNotNull { it.expected["NIROEV_HVBAT_SOH"]?.toDouble() }
        assertTrue(soh.value in sohExpected)
        manager.readings.first { it.sensorId == "01.0C" && it.timestampMs > soh.timestampMs }
        val vdc = readings.first { it.sensorId == "obdb:NIROEV_HVBAT_VDC" }
        assertEquals("V", vdc.unit)
        assertTrue(vdc.value in expected.map { it.expected.getValue("NIROEV_HVBAT_VDC").toDouble() })
        // The steering module 7D4 is on the safety block list: never addressed although the signalset lists it.
        val tx = log.snapshot().filter { it.direction == io.github.iampopovich.eculense.core.obd.TrafficDirection.TX }.map { it.text.replace("\\r", "").trim() }
        assertTrue(tx.none { it.startsWith("ATSH7D4") })
        // TrafficLog order: J1979 requests between two extended group scopes (ATH1 … ATH0).
        val scopes = tx.indices.filter { tx[it] == "ATH1" }
        assertTrue(scopes.size >= 2)
        scopes.zipWithNext().take(3).forEach { (a, b) -> assertTrue(tx.subList(a, b).any { it.startsWith("010C") }) }
        manager.disconnect()
    }
}

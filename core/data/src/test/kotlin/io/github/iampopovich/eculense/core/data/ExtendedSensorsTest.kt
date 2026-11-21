package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.demo.ObdbFixtures
import io.github.iampopovich.eculense.core.model.AdapterInfo
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.FakeConnectionController
import io.github.iampopovich.eculense.core.obdb.EvRole
import io.github.iampopovich.eculense.core.obdb.SignalsetParser
import io.github.iampopovich.eculense.core.obdb.SimulatedSignalset
import io.github.iampopovich.eculense.core.obdb.StoredSignalset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-E: which extended sensors the active link gets (OBDb test-case signalsets, CC BY-SA 4.0, from :core:demo). */
class ExtendedSensorsTest {
    private class MemoryPrefs : ExtendedSensorsPrefs {
        override val enabled = MutableStateFlow(false)
        override suspend fun setEnabled(on: Boolean) {
            enabled.value = on
        }
    }

    private val niro = StoredSignalset("OBDb/Kia-Niro-EV", "test", SignalsetParser.parse(ObdbFixtures.signalset(ObdbFixtures.NIRO_EV)))
    private val sorento = StoredSignalset("OBDb/Kia-Sorento", "test", SignalsetParser.parse(ObdbFixtures.signalset(ObdbFixtures.SORENTO)))
    private val demo = object : SimulatedSignalset {
        override val endpointKind = "demo-niro-ev"
        override val year = 2020
        override fun signalset() = niro
    }

    private fun connected(kind: String, protocol: Char) =
        ConnectionState.Connected(AdapterEndpoint(kind, "x", "x"), AdapterInfo("ELM327 v1.5", protocol, protocol in "6789", 12.6), emptySet())

    @Test
    fun `a simulated EV link uses its own signalset, polls only when switched on`() = runTest {
        val controller = FakeConnectionController(initial = connected("demo-niro-ev", '6'))
        val prefs = MemoryPrefs()
        val car = MutableStateFlow<CarProfile?>(CarProfile(name = "Test car", brand = "Kia", model = "Sorento", year = 2005))
        val sensors = ExtendedSensors(controller, car, load = { _, _, _ -> error("the user's car is never used for a demo link") }, prefs, listOf(demo), backgroundScope)
        sensors.start()
        val ready = sensors.status.first { it is ExtendedStatus.Ready } as ExtendedStatus.Ready
        assertTrue(ready.ev)
        assertTrue(ready.simulated)
        assertEquals(2020, ready.year)
        val soc = ready.signals.first { it.sensorId == "obdb:NIROEV_HVBAT_SOC" }
        assertEquals(EvRole.SOC, soc.role)
        assertEquals("%", soc.unit)
        assertEquals(EvRole.SOH, ready.signals.first { it.sensorId == "obdb:NIROEV_HVBAT_SOH" }.role)
        assertTrue(sensors.electric.first { it })
        assertTrue(controller.extendedBy.value.isEmpty()) // off by default
        sensors.setEnabled(true)
        val groups = controller.extendedBy.first { it.isNotEmpty() }.getValue(ExtendedSensors.OWNER)
        assertTrue(groups.any { it.header == "7E4" })
        assertTrue(groups.first { it.header == "7E4" }.commands.any { it.command.id == "7E4.7EC.220101" })
        sensors.setEnabled(false)
        controller.extendedBy.first { it.isEmpty() }
    }

    @Test
    fun `an old download without the checked years asks for one tap check, then resolves`() = runTest {
        val controller = FakeConnectionController(initial = connected("bt", '6'))
        val prefs = MemoryPrefs()
        val car = MutableStateFlow<CarProfile?>(CarProfile(name = "Test car", brand = "Kia", model = "Niro EV", year = 2020))
        var covered = false
        var checks = 0
        val sensors = ExtendedSensors(
            controller, car, load = { _, _, _ -> if (covered) niro else null }, prefs, emptyList(), backgroundScope,
            needsYearCheck = { _, _ -> !covered },
            checkYear = { _, _, year -> checks++; assertEquals(2020, year); covered = true },
        )
        assertEquals(ExtendedStatus.NeedsYearCheck, sensors.status.first { it !is ExtendedStatus.NotConnected })
        assertEquals(0, checks) // never automatic
        sensors.checkModelYear()
        val ready = sensors.status.first { it is ExtendedStatus.Ready } as ExtendedStatus.Ready
        assertEquals(1, checks)
        assertEquals(2020, ready.year)
    }

    @Test
    fun `a year check that finds no data ends the hint for this process`() = runTest {
        val controller = FakeConnectionController(initial = connected("bt", '6'))
        val car = MutableStateFlow<CarProfile?>(CarProfile(name = "Test car", brand = "Kia", model = "Niro EV", year = 2020))
        val sensors = ExtendedSensors(
            controller, car, load = { _, _, _ -> null }, MemoryPrefs(), emptyList(), backgroundScope,
            needsYearCheck = { _, _ -> true },
            checkYear = { _, _, _ -> },
        )
        assertEquals(ExtendedStatus.NeedsYearCheck, sensors.status.first { it !is ExtendedStatus.NotConnected })
        sensors.checkModelYear()
        assertEquals(ExtendedStatus.NoSignalset(null), sensors.status.first { it is ExtendedStatus.NoSignalset })
    }

    @Test
    fun `display decimals follow the OBDb scale`() {
        val f = io.github.iampopovich.eculense.core.obdb.SignalFormat(len = 8)
        assertEquals(0, ExtendedSensors.decimalsOf(f))
        assertEquals(1, ExtendedSensors.decimalsOf(f.copy(div = 2.0)))
        assertEquals(2, ExtendedSensors.decimalsOf(f.copy(mul = 0.02)))
        assertEquals(1, ExtendedSensors.decimalsOf(null))
    }

    @Test
    fun `a K-Line car without a downloaded signalset gets an honest status and no extended polling`() = runTest {
        val controller = FakeConnectionController(initial = connected("bt", '5'))
        val prefs = MemoryPrefs().apply { enabled.value = true }
        val car = MutableStateFlow<CarProfile?>(CarProfile(name = "Test car", brand = "Kia", model = "Sorento", year = 2005))
        val sensors = ExtendedSensors(controller, car, load = { _, _, _ -> null }, prefs, listOf(demo), backgroundScope)
        sensors.start()
        assertEquals(ExtendedStatus.NoSignalset(null), sensors.status.first { it !is ExtendedStatus.NotConnected })
        assertFalse(sensors.electric.value)
        assertTrue(controller.extendedBy.value.isEmpty())
    }

    @Test
    fun `a CAN signalset on a K-Line link is a protocol mismatch, an unknown model year keeps only unfiltered commands`() = runTest {
        val controller = FakeConnectionController(initial = connected("bt", '5'))
        val prefs = MemoryPrefs().apply { enabled.value = true }
        val car = MutableStateFlow<CarProfile?>(CarProfile(name = "Test car", brand = "Kia", model = "Sorento", year = null))
        val sensors = ExtendedSensors(controller, car, load = { _, _, _ -> sorento }, prefs, emptyList(), backgroundScope)
        sensors.start()
        val r = sensors.status.first { it is ExtendedStatus.Ready } as ExtendedStatus.Ready
        assertTrue(r.protocolMismatch)
        assertTrue(r.yearUnknown)
        assertTrue(r.signals.isEmpty())
        assertFalse(r.ev)
        assertTrue(controller.extendedBy.value.isEmpty())
        // On CAN with the year known the same signalset gives sensors; a 2017 car has no EV battery commands.
        controller.stateFlow.value = connected("bt", '6')
        car.value = car.value!!.copy(year = 2017)
        val can = sensors.status.first { it is ExtendedStatus.Ready && !it.protocolMismatch && it.year == 2017 } as ExtendedStatus.Ready
        assertTrue(can.signals.any { it.sensorId == "obdb:SORENTO_ENGINE_OIL_T" })
        assertFalse(can.ev)
        assertTrue(can.debugSkipped > 0)
    }
}

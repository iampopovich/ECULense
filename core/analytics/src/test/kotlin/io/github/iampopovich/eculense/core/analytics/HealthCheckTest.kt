package io.github.iampopovich.eculense.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthCheckTest {
    private fun input(
        samples: Map<String, List<Double>>,
        stored: List<String>? = emptyList(),
        pending: List<String>? = emptyList(),
        permanentSupported: Boolean = true,
        milOn: Boolean? = false,
        incomplete: Int? = 0,
        lowCounters: Boolean? = false,
    ) = HealthInput(stored, pending, emptyList(), permanentSupported, milOn, supportedMonitors = 6, incompleteMonitors = incomplete, lowClearCounters = lowCounters, samples = samples)

    private val warmIdle = mapOf(
        SensorIds.RPM to List(10) { 760.0 + it * 5 },
        SensorIds.SPEED to List(10) { 0.0 },
        SensorIds.COOLANT to List(5) { 90.0 },
        SensorIds.STFT1 to List(10) { 1.5 },
        SensorIds.LTFT1 to List(10) { 3.0 },
        HealthCheck.O2 to listOf(0.1, 0.8, 0.2, 0.7, 0.15),
        SensorIds.MODULE_VOLTAGE to List(5) { 14.1 },
    )

    private fun HealthResult.of(id: HealthItemId) = items.single { it.id == id }

    @Test
    fun `a healthy warm idle car is OK in every judged item`() {
        val r = HealthCheck.evaluate(input(warmIdle))
        assertEquals(HealthStatus.OK, r.verdict)
        assertEquals(HealthItemId.entries.size, r.items.size)
        assertEquals(HealthStatus.OK, r.of(HealthItemId.FUEL_TRIMS).status)
        assertEquals(HealthStatus.OK, r.of(HealthItemId.O2_SENSOR).status)
        assertEquals(HealthStatus.OK, r.of(HealthItemId.IDLE).status)
    }

    @Test
    fun `stored codes and check engine are faults, pending codes and incomplete monitors need attention`() {
        val r = HealthCheck.evaluate(input(warmIdle, stored = listOf("P0133"), pending = listOf("P0171"), milOn = true, incomplete = 2))
        assertEquals(HealthStatus.FAULT, r.verdict)
        assertEquals(HealthStatus.FAULT, r.of(HealthItemId.STORED_CODES).status)
        assertEquals(1.0, r.of(HealthItemId.STORED_CODES).value!!, 0.0)
        assertEquals(HealthStatus.ATTENTION, r.of(HealthItemId.PENDING_CODES).status)
        assertEquals(HealthStatus.ATTENTION, r.of(HealthItemId.READINESS).status)
        assertEquals(HealthStatus.FAULT, r.of(HealthItemId.CHECK_ENGINE).status)
    }

    @Test
    fun `hot coolant with the engine off is residual heat, not a fault`() {
        val off = mapOf(SensorIds.RPM to List(5) { 0.0 }, SensorIds.COOLANT to listOf(112.0), SensorIds.ELM_VOLTAGE to listOf(12.5), SensorIds.LTFT1 to listOf(12.0))
        val r = HealthCheck.evaluate(input(off))
        assertEquals(HealthStatus.OK, r.of(HealthItemId.COOLANT).status)
        assertEquals(HealthNote.RESIDUAL_HEAT, r.of(HealthItemId.COOLANT).note)
        assertEquals(HealthStatus.NOT_JUDGED, r.of(HealthItemId.FUEL_TRIMS).status)
        assertEquals(HealthNote.RESTING_VOLTAGE, r.of(HealthItemId.VOLTAGE).note)
        val running = HealthCheck.evaluate(input(warmIdle + (SensorIds.COOLANT to listOf(112.0))))
        assertEquals(HealthStatus.FAULT, running.of(HealthItemId.COOLANT).status)
    }

    @Test
    fun `a cold engine does not judge trims, O2 or idle`() {
        val cold = warmIdle + (SensorIds.COOLANT to listOf(40.0)) + (SensorIds.LTFT1 to List(5) { 20.0 }) + (SensorIds.RPM to List(5) { 1200.0 })
        val r = HealthCheck.evaluate(input(cold))
        listOf(HealthItemId.FUEL_TRIMS, HealthItemId.O2_SENSOR, HealthItemId.IDLE).forEach {
            assertEquals(it.name, HealthStatus.NOT_JUDGED, r.of(it).status)
            assertEquals(HealthNote.COLD_ENGINE, r.of(it).note)
        }
        assertEquals(HealthStatus.OK, r.verdict)
    }

    @Test
    fun `lean trims, lazy O2, hunting idle and low charging voltage`() {
        val bad = warmIdle + (SensorIds.LTFT1 to List(5) { 14.0 }) + (HealthCheck.O2 to listOf(0.45, 0.47, 0.46)) +
            (SensorIds.RPM to listOf(650.0, 900.0, 700.0)) + (SensorIds.MODULE_VOLTAGE to List(3) { 12.9 })
        val r = HealthCheck.evaluate(input(bad))
        assertEquals(HealthStatus.ATTENTION, r.of(HealthItemId.FUEL_TRIMS).status)
        assertEquals(HealthNote.O2_LAZY, r.of(HealthItemId.O2_SENSOR).note)
        assertEquals(HealthNote.UNSTABLE_IDLE, r.of(HealthItemId.IDLE).note)
        assertEquals(HealthStatus.ATTENTION, r.of(HealthItemId.VOLTAGE).status)
        val worseSamples = warmIdle + (SensorIds.STFT1 to List(5) { 15.0 }) + (SensorIds.LTFT1 to List(5) { 14.0 })
        // generic ±25 % is not verified for this engine: capped at ATTENTION, the norm rides with the item
        val worse = HealthCheck.evaluate(input(worseSamples))
        assertEquals(HealthStatus.ATTENTION, worse.of(HealthItemId.FUEL_TRIMS).status)
        assertEquals(NormSource.GENERIC, worse.of(HealthItemId.FUEL_TRIMS).norm?.source)
        // the user's own norm keeps the full severity
        val user = CarNorms(listOf(Norm(NormKey.TOTAL_TRIM, -20.0, 20.0, NormSource.USER)))
        assertEquals(HealthStatus.FAULT, HealthCheck.evaluate(input(worseSamples), user).of(HealthItemId.FUEL_TRIMS).status)
    }

    @Test
    fun `what the ECU does not have is marked not supported`() {
        val r = HealthCheck.evaluate(input(mapOf(SensorIds.RPM to listOf(800.0)), stored = null, permanentSupported = false, milOn = null, incomplete = null, lowCounters = null))
        listOf(HealthItemId.STORED_CODES, HealthItemId.PERMANENT_CODES, HealthItemId.CHECK_ENGINE, HealthItemId.READINESS, HealthItemId.RECENT_CLEAR, HealthItemId.COOLANT, HealthItemId.VOLTAGE, HealthItemId.FUEL_TRIMS, HealthItemId.O2_SENSOR)
            .forEach { assertEquals(it.name, HealthStatus.NOT_SUPPORTED, r.of(it).status) }
        assertEquals(HealthNote.CAN_ONLY, r.of(HealthItemId.PERMANENT_CODES).note)
    }

    @Test
    fun `recently cleared counters need attention`() {
        assertEquals(HealthStatus.ATTENTION, HealthCheck.evaluate(input(warmIdle, lowCounters = true)).of(HealthItemId.RECENT_CLEAR).status)
    }
}

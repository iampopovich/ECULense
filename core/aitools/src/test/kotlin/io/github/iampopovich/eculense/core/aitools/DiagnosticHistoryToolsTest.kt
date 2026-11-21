package io.github.iampopovich.eculense.core.aitools

import io.github.iampopovich.eculense.core.analytics.HealthItem
import io.github.iampopovich.eculense.core.analytics.HealthItemId
import io.github.iampopovich.eculense.core.analytics.HealthNote
import io.github.iampopovich.eculense.core.analytics.HealthResult
import io.github.iampopovich.eculense.core.analytics.HealthStatus
import io.github.iampopovich.eculense.core.data.AnalysisState
import io.github.iampopovich.eculense.core.data.AnalyticsRepository
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.DtcRepository
import io.github.iampopovich.eculense.core.data.HealthCheckRecord
import io.github.iampopovich.eculense.core.data.HealthRepository
import io.github.iampopovich.eculense.core.data.ReadinessReason
import io.github.iampopovich.eculense.core.data.ReadinessSnapshot
import io.github.iampopovich.eculense.core.data.TripRepository
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.obd.ClearedCounters
import io.github.iampopovich.eculense.core.obd.ReadinessDecoder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** R1.6: readinessHistory and healthChecks tools (English, compact, no car name/VIN, stored real-car data only). */
class DiagnosticHistoryToolsTest {
    private val zone = ZoneOffset.UTC
    private fun ms(d: String) = LocalDate.parse(d).atStartOfDay(zone).toInstant().toEpochMilli()
    private val now = ms("2026-06-01")
    private val car = CarProfile(id = 1, name = "Тестовая", vin = "KNAJC526X55123456")

    private val cars = object : CarRepository {
        override fun observeCars(): Flow<List<CarProfile>> = flowOf(listOf(car))
        override fun observeActiveCar(): Flow<CarProfile?> = flowOf(car)
        override suspend fun activeCarId() = car.id
        override suspend fun cars() = listOf(car)
        override suspend fun byExternalId(externalId: String): CarProfile? = null
        override suspend fun save(car: CarProfile) = car.id
        override suspend fun delete(id: Long) = false
        override suspend fun setActive(id: Long) = Unit
    }
    private val trips = object : TripRepository {
        override fun observeTrips(carId: Long): Flow<List<Trip>> = flowOf(emptyList())
        override suspend fun addAll(trips: List<Trip>) = 0
    }

    // PID 01 A=0x82 (MIL on, 2 codes), B=0x07 (continuous supported), C=0x65 (catalyst, evap, O2, O2 heater), D=0x21 (catalyst + O2 incomplete).
    private val milOn = ReadinessDecoder.status(intArrayOf(0x82, 0x07, 0x65, 0x21))
    private val ready = ReadinessDecoder.status(intArrayOf(0x00, 0x07, 0x65, 0x00))

    private class Snapshots(val list: List<ReadinessSnapshot>) : DtcRepository {
        override suspend fun saveSnapshot(dtcs: List<Dtc>, vin: String?, takenAtMs: Long?, carId: Long?) = error("tool must not write")
        override suspend fun readinessHistory(carId: Long, limit: Int) = list.sortedByDescending { it.takenAtMs }.take(limit)
    }

    private class Checks(val list: List<HealthCheckRecord>) : HealthRepository {
        override fun observe(carId: Long?) = flowOf(list.filter { it.carId == carId })
        override suspend fun byId(id: Long) = list.firstOrNull { it.id == id }
        override suspend fun save(record: HealthCheckRecord): Long = error("tool must not write")
    }

    private fun check(id: Long, at: Long, verdict: HealthStatus, readiness: io.github.iampopovich.eculense.core.obd.Readiness?, counters: ClearedCounters?) = HealthCheckRecord(
        id = id, carId = 1, takenAtMs = at,
        result = HealthResult(
            verdict,
            listOf(
                HealthItem(HealthItemId.STORED_CODES, HealthStatus.FAULT, 2.0),
                HealthItem(HealthItemId.VOLTAGE, HealthStatus.OK, 12.6, HealthNote.RESTING_VOLTAGE),
                HealthItem(HealthItemId.RECENT_CLEAR, HealthStatus.ATTENTION, note = HealthNote.CLEARED_RECENTLY),
            ),
        ),
        dtcs = listOf(Dtc("P0133", DtcKind.STORED)), freezeFrame = null, readiness = readiness, counters = counters, odometerKm = 150_000,
    )

    private fun ctx(dtc: DtcRepository?, health: HealthRepository?) = ToolContext(
        noAnalytics(), cars, trips, clock = { now }, zone = { zone }, log = { _, _, _ -> }, dtc = dtc, health = health,
    )

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `latest readiness with counters and the clear made in the app`() = runBlocking {
        val snapshots = Snapshots(
            listOf(
                ReadinessSnapshot(ms("2026-05-10"), ReadinessReason.BEFORE_CLEAR, milOn, ClearedCounters(warmUpsSinceClear = 40, kmSinceClear = 2_000)),
                ReadinessSnapshot(ms("2026-05-10") + 60_000, ReadinessReason.AFTER_CLEAR, ready.copy(), ClearedCounters(warmUpsSinceClear = 0, kmSinceClear = 0)),
            ),
        )
        val checks = Checks(listOf(check(5, ms("2026-05-20"), HealthStatus.ATTENTION, milOn, ClearedCounters(warmUpsSinceClear = 3, kmSinceClear = 85, minutesSinceClear = 150))))
        val json = ReadinessHistoryTool(ctx(snapshots, checks)).execute(LimitArgs())
        assertFalse(json, json.contains("Тестовая") || json.contains("KNAJC"))
        assertTrue(json.toByteArray().size <= Compact.MAX_BYTES)
        val o = obj(json)
        assertEquals("health_check", o.getValue("origin").jsonPrimitive.content)
        assertEquals("on", o.getValue("check_engine_lamp").jsonPrimitive.content)
        assertEquals(listOf("catalyst", "oxygen sensor"), o.getValue("monitors_incomplete").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("no", o.getValue("obd_inspection_ready").jsonPrimitive.content)
        assertEquals("3", o.getValue("warm_ups_since_clear").jsonPrimitive.content)
        assertEquals("2.5", o.getValue("hours_since_clear").jsonPrimitive.content)
        assertEquals(listOf("2026-05-10"), o.getValue("cleared_in_app_on").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("after_clear", "before_clear"), o.getValue("older").jsonArray.map { it.jsonObject.getValue("origin").jsonPrimitive.content })
    }

    @Test
    fun `no stored readiness is an explicit no_data`() = runBlocking {
        assertTrue(obj(ReadinessHistoryTool(ctx(Snapshots(emptyList()), Checks(emptyList()))).execute(LimitArgs())).containsKey("no_data"))
        assertTrue(obj(ReadinessHistoryTool(ctx(null, null)).execute(LimitArgs())).containsKey("no_data"))
        val never = obj(ReadinessHistoryTool(ctx(null, Checks(listOf(check(5, ms("2026-05-20"), HealthStatus.OK, ready, null))))).execute(LimitArgs()))
        assertEquals("never", never.getValue("cleared_in_app_on").jsonPrimitive.content)
        assertEquals("yes", never.getValue("obd_inspection_ready").jsonPrimitive.content)
    }

    @Test
    fun `health checks list verdict and items newest first without car name`() = runBlocking {
        val checks = Checks(listOf(check(5, ms("2026-05-20"), HealthStatus.ATTENTION, milOn, null), check(6, ms("2026-05-25"), HealthStatus.FAULT, null, null)))
        val json = HealthChecksTool(ctx(null, checks)).execute(LimitArgs(3))
        assertFalse(json, json.contains("Тестовая") || json.contains("KNAJC"))
        val list = obj(json).getValue("checks").jsonArray.map { it.jsonObject }
        assertEquals(listOf("fault", "attention"), list.map { it.getValue("verdict").jsonPrimitive.content })
        val items = list.first().getValue("items").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(items.toString(), "voltage: ok, 12.6, resting voltage" in items)
        assertTrue(items.toString(), "recent clear: attention, cleared recently" in items)
        assertEquals("P0133 stored", list.first().getValue("codes").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `demo checks never appear - only stored records with an id count`() = runBlocking {
        // A simulated link's check is never stored (id = 0, HealthCheckRunner skips save); even if one leaks into the list, the tools skip it.
        val checks = Checks(listOf(check(0, ms("2026-05-30"), HealthStatus.FAULT, milOn, ClearedCounters(warmUpsSinceClear = 1))))
        assertTrue(obj(HealthChecksTool(ctx(null, checks)).execute(LimitArgs())).containsKey("no_data"))
        assertTrue(obj(ReadinessHistoryTool(ctx(null, checks)).execute(LimitArgs())).containsKey("no_data"))
    }

    @Test
    fun `digest sections render as plain English lines`() = runBlocking {
        val checks = Checks(listOf(check(5, ms("2026-05-20"), HealthStatus.ATTENTION, milOn, ClearedCounters(warmUpsSinceClear = 3))))
        val text = VehicleDataToolbox(ctx(Snapshots(emptyList()), checks)).digest(listOf("readinessHistory", "healthChecks"), 180)!!
        assertTrue(text, text.lines().first().startsWith("OBD readiness: "))
        assertTrue(text, text.contains("check engine lamp: on"))
        assertTrue(text, text.contains("origin: health check"))
        assertTrue(text, text.contains("Quick health checks:"))
        listOf("{", "}", "\"").forEach { assertFalse(text, text.contains(it)) }
        assertFalse(text, Regex("\\b[a-z]+_[a-z_]+\\b").containsMatchIn(text))
    }
}

private fun noAnalytics(): AnalyticsRepository = object : AnalyticsRepository {
    override val state = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    override fun observeFindings(carId: Long) = error("unused")
    override suspend fun findings(carId: Long) = error("unused")
    override suspend fun finding(id: Long) = error("unused")
    override suspend fun recompute(carId: Long) = error("unused")
    override suspend fun sessions(carId: Long, fromMs: Long, toMs: Long, limit: Int) = error("unused")
    override suspend fun sessionReport(carId: Long, sessionId: Long) = error("unused")
    override suspend fun sensorSummary(carId: Long, sensorId: String, fromMs: Long, toMs: Long, warmOnly: Boolean) = error("unused")
    override suspend fun dtcEvents(carId: Long) = error("unused")
}

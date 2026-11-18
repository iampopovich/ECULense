package io.github.iampopovich.eculense.core.aitools

import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import io.github.iampopovich.eculense.core.analytics.MaintenanceRule
import io.github.iampopovich.eculense.core.analytics.OdometerEstimate
import io.github.iampopovich.eculense.core.analytics.OdometerEstimator
import io.github.iampopovich.eculense.core.data.AnalysisState
import io.github.iampopovich.eculense.core.data.AnalyticsRepository
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.MaintenanceRepository
import io.github.iampopovich.eculense.core.data.TripRepository
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import io.github.iampopovich.eculense.core.model.OdometerEntry
import io.github.iampopovich.eculense.core.model.Trip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class MaintenanceDueToolTest {
    private val zone = ZoneOffset.UTC
    private fun ms(d: String) = LocalDate.parse(d).atStartOfDay(zone).toInstant().toEpochMilli()
    private val now = ms("2026-06-01")

    private class OneCar(private val car: CarProfile) : CarRepository {
        override fun observeCars(): Flow<List<CarProfile>> = flowOf(listOf(car))
        override fun observeActiveCar(): Flow<CarProfile?> = flowOf(car)
        override suspend fun activeCarId() = car.id
        override suspend fun cars() = listOf(car)
        override suspend fun byExternalId(externalId: String): CarProfile? = null
        override suspend fun save(car: CarProfile) = car.id
        override suspend fun delete(id: Long) = false
        override suspend fun setActive(id: Long) = Unit
    }

    private inner class Mem(val items: List<MaintenanceItem>, val log: List<OdometerEntry>) : MaintenanceRepository {
        override fun observeItems(carId: Long) = flowOf(items)
        override suspend fun items(carId: Long) = items
        override suspend fun ensureTemplate(car: CarProfile) = error("tool must not write")
        override suspend fun save(item: MaintenanceItem): Long = error("tool must not write")
        override suspend fun delete(id: Long) = error("tool must not write")
        override suspend fun markDone(id: Long, km: Long?, atMs: Long): MaintenanceItem? = error("tool must not write")
        override fun observeOdometer(carId: Long) = flowOf(log)
        override suspend fun addOdometer(carId: Long, km: Long, atMs: Long): Long = error("tool must not write")
        override suspend fun deleteOdometer(id: Long) = error("tool must not write")
        override fun observeEstimate(carId: Long): Flow<OdometerEstimate?> = flowOf(estimate(log))
        override suspend fun estimate(carId: Long) = estimate(log)
        private fun estimate(l: List<OdometerEntry>) = OdometerEstimator.estimate(l, emptyList())?.let { it.copy(km = it.km + 250.0, addedKm = 250.0) }
        override suspend fun evaluate(carId: Long): List<MaintenanceDue> = MaintenanceRule.evaluateAll(items, estimate(carId)?.km, now, zone)
        override suspend fun due(carId: Long): List<MaintenanceDue> = MaintenanceRule.due(items, estimate(carId)?.km, now, zone)
    }

    private val noAnalytics = unused()
    private val noTrips = object : TripRepository {
        override fun observeTrips(carId: Long): Flow<List<Trip>> = flowOf(emptyList())
        override suspend fun addAll(trips: List<Trip>) = 0
    }

    private fun ctx(m: MaintenanceRepository?) = ToolContext(
        noAnalytics, OneCar(CarProfile(id = 1, name = "Тестовая", vin = "KNAJC526X55123456")), noTrips,
        clock = { now }, zone = { zone }, log = { _, _, _ -> }, maintenance = m,
    )

    @Test
    fun `due works come first with km and days left and the odometer is marked as estimated`() = runBlocking {
        val items = listOf(
            MaintenanceItem(1, 1, "Масло и масляный фильтр двигателя", 10_000, 12, 50_000, ms("2026-01-01")),
            MaintenanceItem(2, 1, "Тормозная жидкость", null, 24, null, ms("2024-05-01")),
            MaintenanceItem(3, 1, "Ремень/цепь ГРМ", null, null, null, null, "Интервал — по регламенту двигателя"),
        )
        val json = MaintenanceDueTool(ctx(Mem(items, listOf(OdometerEntry(9, 1, 60_000, ms("2026-05-01")))))).execute(NoArgs())
        assertFalse(json.contains("KNAJC"))
        assertTrue(json.toByteArray().size <= Compact.MAX_BYTES)
        val o = Json.parseToJsonElement(json).jsonObject
        assertEquals(60_250, o.getValue("odometer_km").jsonPrimitive.int)
        assertEquals("true", o.getValue("odometer_estimated").jsonPrimitive.content)
        assertEquals("2026-05-01", o.getValue("odometer_entered_date").jsonPrimitive.content)
        val works = o.getValue("works").jsonArray.map { it.jsonObject }
        assertEquals(listOf("overdue", "overdue", "unknown"), works.map { it.getValue("status").jsonPrimitive.content })
        val oil = works.first { it.getValue("work").jsonPrimitive.content.startsWith("Масло") }
        assertEquals(-250, oil.getValue("km_left").jsonPrimitive.int)
        assertEquals("2027-01-01", oil.getValue("due_date").jsonPrimitive.content)
        assertEquals(o.getValue("due_now").jsonPrimitive.int, 2)
    }

    @Test
    fun `no list yet and no module are explicit no_data`() = runBlocking {
        val empty = MaintenanceDueTool(ctx(Mem(emptyList(), emptyList()))).execute(NoArgs())
        assertTrue(empty.contains("no_data"))
        assertTrue(empty.contains("Обслуживание"))
        assertTrue(MaintenanceDueTool(ctx(null)).execute(NoArgs()).contains("no_data"))
    }
}

/** Делегат-заглушка: инструмент ТО к аналитике не обращается. */
private fun unused(): AnalyticsRepository = object : AnalyticsRepository {
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

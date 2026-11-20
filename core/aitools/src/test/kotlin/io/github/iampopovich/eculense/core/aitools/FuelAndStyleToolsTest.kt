package io.github.iampopovich.eculense.core.aitools

import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.SensorIds
import io.github.iampopovich.eculense.core.data.AnalysisState
import io.github.iampopovich.eculense.core.data.AnalyticsRepository
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.ExpenseRepository
import io.github.iampopovich.eculense.core.data.FuelImportResult
import io.github.iampopovich.eculense.core.data.FuelPrefs
import io.github.iampopovich.eculense.core.data.FuelRepository
import io.github.iampopovich.eculense.core.data.SensorSummary
import io.github.iampopovich.eculense.core.data.SessionReport
import io.github.iampopovich.eculense.core.data.SessionRepository
import io.github.iampopovich.eculense.core.data.SessionSummary
import io.github.iampopovich.eculense.core.data.StoredFinding
import io.github.iampopovich.eculense.core.data.TripRepository
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.Expense
import io.github.iampopovich.eculense.core.model.ExpenseCategory
import io.github.iampopovich.eculense.core.model.FuelFillUp
import io.github.iampopovich.eculense.core.model.FuelMethod
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.Session
import io.github.iampopovich.eculense.core.model.SessionSource
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

private const val D = 86_400_000L
private const val T0 = 1_779_000_000_000L

class FuelAndStyleToolsTest {
    private val car = CarProfile(id = 1, name = "Test car", tankVolumeL = 60.0, currency = "EUR", vin = "TESTVIN0000000001")

    private val cars = object : CarRepository {
        override fun observeCars(): Flow<List<CarProfile>> = flowOf(listOf(car))
        override fun observeActiveCar(): Flow<CarProfile?> = flowOf(car)
        override suspend fun activeCarId(): Long = 1
        override suspend fun cars() = listOf(car)
        override suspend fun byExternalId(externalId: String): CarProfile? = null
        override suspend fun save(car: CarProfile) = car.id
        override suspend fun delete(id: Long) = false
        override suspend fun setActive(id: Long) = Unit
    }

    /** 1 km/min cruise with one harsh start: speed and rpm every second, trims on a warm engine. */
    private val readings: List<SensorReading> = buildList {
        add(SensorReading(SensorIds.COOLANT, 90.0, "", T0 - 10 * D))
        for (i in 0 until 600) {
            val t = T0 - 10 * D + i * 1000L
            val v = if (i < 4) i * 15.0 else 60.0
            add(SensorReading(SensorIds.SPEED, v, "", t))
            add(SensorReading(SensorIds.RPM, if (i % 2 == 0) 800.0 else 3000.0, "", t))
            add(SensorReading(SensorIds.LTFT1, 4.0, "", t))
            add(SensorReading(SensorIds.STFT1, 2.0, "", t + 5))
        }
    }

    private val trip = Trip(10, 1, T0 - 10 * D, T0 - 10 * D + 600_000, 9.9, 0.9, 50.0, 590_000, 10_000, TripSource.LIVE, "session:5", FuelMethod.MAF_ESTIMATE)

    private val trips = object : TripRepository {
        override fun observeTrips(carId: Long): Flow<List<Trip>> = flowOf(listOf(trip))
        override suspend fun addAll(trips: List<Trip>) = 0
    }

    private val sessions = object : SessionRepository {
        override fun observeSessions(): Flow<List<Session>> = flowOf(listOf(Session(5, "s", T0 - 10 * D, T0 - 10 * D + 600_000, SessionSource.LIVE)))
        override suspend fun start(name: String, source: SessionSource, vin: String?, startedAtMs: Long?, externalId: String?, carId: Long?) = 0L
        override suspend fun append(sessionId: Long, readings: List<SensorReading>) = Unit
        override suspend fun finish(sessionId: Long, endedAtMs: Long?) = Unit
        override suspend fun readings(sessionId: Long) = if (sessionId == 5L) readings else emptyList()
        override suspend fun delete(sessionId: Long) = Unit
        override suspend fun hasExternal(externalId: String) = false
        override suspend fun assignCar(externalId: String, carId: Long) = Unit
    }

    private val analytics = object : AnalyticsRepository {
        override val state = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
        override fun observeFindings(carId: Long) = flowOf(emptyList<StoredFinding>())
        override suspend fun findings(carId: Long) = emptyList<StoredFinding>()
        override suspend fun finding(id: Long): StoredFinding? = null
        override suspend fun recompute(carId: Long) = emptyList<Finding>()
        override suspend fun sessions(carId: Long, fromMs: Long, toMs: Long, limit: Int) =
            listOf(SessionSummary(5, T0 - 10 * D, T0 - 10 * D + 600_000, SessionSource.LIVE, readings.size))
        override suspend fun sessionReport(carId: Long, sessionId: Long): SessionReport? = null
        override suspend fun sensorSummary(carId: Long, sensorId: String, fromMs: Long, toMs: Long, warmOnly: Boolean): SensorSummary? = null
        override suspend fun dtcEvents(carId: Long) = emptyList<io.github.iampopovich.eculense.core.analytics.DtcEvent>()
    }

    private val fills = listOf(
        FuelFillUp(1, 1, T0 - 30 * D, 10_000, 40.0, 50.0, 2000.0),
        FuelFillUp(2, 1, T0 - 5 * D, 10_500, 45.0, 50.0, 2250.0),
    )

    private val fuel = object : FuelRepository {
        override fun observeFillUps(carId: Long) = flowOf(fills)
        override suspend fun fillUps(carId: Long) = fills
        override suspend fun save(fillUp: FuelFillUp) = 0L
        override suspend fun delete(id: Long) = Unit
        override suspend fun import(rows: List<FuelFillUp>) = FuelImportResult(0, 0)
    }

    private val prefs = object : FuelPrefs {
        override fun mafCorrection(carId: Long) = flowOf(false)
        override suspend fun setMafCorrection(carId: Long, on: Boolean) = Unit
    }

    private val expenses = object : ExpenseRepository {
        val list = listOf(Expense(1, 1, T0 - 3 * D, ExpenseCategory.INSURANCE, 600.0))
        override fun observeExpenses(carId: Long) = flowOf(list)
        override suspend fun expenses(carId: Long) = list
        override suspend fun save(expense: Expense) = 0L
        override suspend fun delete(id: Long) = Unit
    }

    private val ctx = ToolContext(
        analytics, cars, trips, clock = { T0 }, zone = { ZoneOffset.UTC }, log = { _, _, _ -> },
        fuel = fuel, fuelPrefs = prefs, expenses = expenses, sessions = sessions,
    )

    @Test
    fun `fuel log tool gives real consumption range and no vin`() = runBlocking {
        val json = FuelLogTool(ctx).json(DaysArgs(0))
        assertEquals(9.0, json["consumption_l_per_100km"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(667, json["range_full_tank_km"]!!.jsonPrimitive.int)
        assertFalse(json.toString().contains("TESTVIN"))
        assertTrue(Compact.bytes(json) <= Compact.MAX_BYTES)
    }

    @Test
    fun `expenses tool totals fuel and categories`() = runBlocking {
        val json = ExpensesTool(ctx).json(DaysArgs(0))
        assertEquals(4850, json["total"]!!.jsonPrimitive.int)
        assertEquals(600, json["by_category"]!!.jsonObject["insurance"]!!.jsonPrimitive.int)
    }

    @Test
    fun `engine map and driving style tools read the latest recording`() = runBlocking {
        val map = EngineMapTool(ctx).json(EngineMapArgs())
        assertEquals(5, map["session_id"]!!.jsonPrimitive.int)
        assertTrue(map["hypothesis"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(map["by_rpm"]!!.jsonArray.isNotEmpty())
        val style = DrivingStyleTool(ctx).json(StyleArgs())
        val first = style["trips"]!!.jsonArray.first().jsonObject
        assertTrue(first["summary"]!!.jsonPrimitive.content, first["summary"]!!.jsonPrimitive.content.contains("harsh accelerations 1"))
    }

    @Test
    fun `without the fuel module the tools say so`() = runBlocking {
        val bare = ToolContext(analytics, cars, trips, log = { _, _, _ -> })
        assertTrue(FuelLogTool(bare).json(DaysArgs()).containsKey("no_data"))
        assertTrue(ExpensesTool(bare).json(DaysArgs()).containsKey("no_data"))
    }
}

package io.github.iampopovich.eculense.core.aitools

import io.github.iampopovich.eculense.core.analytics.DtcEvent
import io.github.iampopovich.eculense.core.analytics.Evidence
import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.data.AnalysisState
import io.github.iampopovich.eculense.core.data.AnalyticsRepository
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.SensorSummary
import io.github.iampopovich.eculense.core.data.SessionReport
import io.github.iampopovich.eculense.core.data.SessionSummary
import io.github.iampopovich.eculense.core.data.StoredFinding
import io.github.iampopovich.eculense.core.data.TripRepository
import io.github.iampopovich.eculense.core.dtcdb.DtcInfo
import io.github.iampopovich.eculense.core.dtcdb.DtcSource
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.SessionSource
import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripSource
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
import java.time.ZoneOffset

private const val VIN = "KNAJC526X55123456"
private const val DAY = 86_400_000L
private const val NOW = 1_779_000_000_000L

private class Cars(var car: CarProfile?) : CarRepository {
    override fun observeCars(): Flow<List<CarProfile>> = flowOf(listOfNotNull(car))
    override fun observeActiveCar(): Flow<CarProfile?> = flowOf(car)
    override suspend fun activeCarId(): Long? = car?.id
    override suspend fun cars() = listOfNotNull(car)
    override suspend fun byExternalId(externalId: String): CarProfile? = null
    override suspend fun save(car: CarProfile) = car.id
    override suspend fun delete(id: Long) = false
    override suspend fun setActive(id: Long) = Unit
}

private class Trips(val list: List<Trip>) : TripRepository {
    override fun observeTrips(carId: Long): Flow<List<Trip>> = flowOf(list.filter { it.carId == carId })
    override suspend fun addAll(trips: List<Trip>) = 0
}

/** Много данных, чтобы проверить бюджет ответа: 24 датчика, 40 кодов, 15 находок, координаты в сессии. */
private class Analytics : AnalyticsRepository {
    var stored = emptyList<StoredFinding>()
    var recomputes = 0
    val requests = mutableListOf<String>()
    override val state = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    override fun observeFindings(carId: Long) = flowOf(stored)
    override suspend fun findings(carId: Long) = stored
    override suspend fun finding(id: Long) = stored.firstOrNull { it.id == id }
    override suspend fun recompute(carId: Long): List<Finding> {
        recomputes++
        stored = (1..15).map { i ->
            val sev = when { i <= 2 -> Severity.CRITICAL; i <= 8 -> Severity.WARNING; i <= 10 -> Severity.NO_DATA; else -> Severity.OK }
            StoredFinding(i.toLong(), carId, Finding("rule-$i", sev, "Находка номер $i с длинным заголовком", "Сообщение ".repeat(20),
                (1..5).map { Evidence("Доказательство $it", "+12,5 % (запись 8 мая 2026, замеров: 4)") }, listOf(1)), NOW)
        }
        return stored.map { it.finding }
    }
    override suspend fun sessions(carId: Long, fromMs: Long, toMs: Long, limit: Int): List<SessionSummary> {
        requests += "sessions $fromMs $toMs $limit"
        return (1..limit).map { SessionSummary(it.toLong(), NOW - it * DAY, NOW - it * DAY + 1_800_000, SessionSource.IMPORT_CARSCANNER, 17_000) }
    }
    override suspend fun sessionReport(carId: Long, sessionId: Long): SessionReport = SessionReport(
        SessionSummary(sessionId, NOW - DAY, NOW - DAY + 3_600_000, SessionSource.IMPORT_CARSCANNER, 100_000),
        // репозиторий уже убирает координаты; здесь проверяем, что и сами инструменты ничего такого не добавляют
        (1..24).map { SensorSummary("01.%02X".format(it), "u", 10_000, 1, 1.23456, 99.98765, 50.5555, 50.0, 95.0) },
        listOf(Finding("coolant-overheat", Severity.WARNING, "Перегрев", "m", listOf(Evidence("Максимум ОЖ", "106,0 °C")))),
    )
    override suspend fun sensorSummary(carId: Long, sensorId: String, fromMs: Long, toMs: Long, warmOnly: Boolean): SensorSummary? {
        requests += "sensor $sensorId $fromMs $warmOnly"
        return if (sensorId == "01.07") SensorSummary("01.07", "%", 4, 2, -8.59375, 12.5, 7.03125, 12.5, 12.5) else null
    }
    override suspend fun dtcEvents(carId: Long) = (1..40).flatMap { i ->
        listOf(DtcEvent("P0%03d".format(100 + i), DtcKind.STORED, NOW - i * DAY), DtcEvent("P0%03d".format(100 + i), DtcKind.STORED, NOW - i * DAY + 5 * DAY))
    }
}

class VehicleDataToolsTest {
    private val car = CarProfile(id = 1, name = "Тестовая", brand = "Kia", year = 2005, vin = VIN, externalId = "carscanner:7", currency = "RUB")
    private val trips = listOf(
        Trip(1, 1, NOW - 2 * DAY, NOW - 2 * DAY + 1_800_000, 20.0, 2.4, 60.0, 1_500_000, 300_000, TripSource.IMPORT_CARSCANNER),
        Trip(2, 1, NOW - 40 * DAY, NOW - 40 * DAY + 3_600_000, 50.0, 6.0, 60.0, 3_000_000, 600_000, TripSource.IMPORT_CARSCANNER),
    )
    private val analytics = Analytics()
    private val logged = mutableListOf<Pair<String, Int>>()
    private val ctx = ToolContext(analytics, Cars(car), Trips(trips), clock = { NOW }, zone = { ZoneOffset.UTC }, log = { t, b, _ -> logged += t to b })
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun all(): List<String> = runBlocking {
        listOf(
            CarProfileTool(ctx).execute(NoArgs()),
            TripStatsTool(ctx).execute(PeriodArgs("all")),
            ListSessionsTool(ctx).execute(ListSessionsArgs(limit = 50)),
            SessionStatsTool(ctx).execute(SessionArgs(1)),
            SensorSummaryTool(ctx).execute(SensorArgs("01.07", warmOnly = true)),
            FindingsTool(ctx).execute(LimitArgs(20)),
            DtcHistoryTool(ctx).execute(LimitArgs(30)),
            MaintenanceDueTool(ctx).execute(NoArgs()),
        )
    }

    @Test
    fun `every answer fits the 2 KB budget and is logged with its size`() {
        val answers = all()
        answers.forEach { assertTrue("${it.toByteArray().size}: $it", it.toByteArray().size <= Compact.MAX_BYTES) }
        assertEquals(listOf("carProfile", "tripStats", "listSessions", "sessionStats", "sensorSummary", "findings", "dtcHistory", "maintenanceDue"), logged.map { it.first })
        assertEquals(answers.map { it.toByteArray().size }, logged.map { it.second })
    }

    @Test
    fun `no VIN, car name, external id or coordinates in any answer`() {
        all().forEach { a ->
            assertFalse(a, a.contains(VIN))
            assertFalse(a, a.contains("Тестовая"))
            assertFalse(a, a.contains("carscanner:7"))
            assertFalse(a, Regex("\"(lat|lon|latitude|longitude|gps)", RegexOption.IGNORE_CASE).containsMatchIn(a))
        }
    }

    @Test
    fun `car profile names missing engine volume`() = runBlocking {
        val p = obj(CarProfileTool(ctx).execute(NoArgs()))
        assertEquals("Kia", p.getValue("brand").jsonPrimitive.content)
        assertEquals(2005, p.getValue("year").jsonPrimitive.content.toInt())
        assertTrue(p.getValue("missing").jsonArray.map { it.jsonPrimitive.content }.contains("engine_l"))
    }

    @Test
    fun `trip stats per period with last trip`() = runBlocking {
        val month = obj(TripStatsTool(ctx).execute(PeriodArgs("month")))
        assertEquals(1, month.getValue("trips").jsonPrimitive.content.toInt())
        assertEquals("12.0", month.getValue("l_per_100km").jsonPrimitive.content)
        assertEquals(20.0, month.getValue("last_trip").jsonObject.getValue("km").jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(2, obj(TripStatsTool(ctx).execute(PeriodArgs("all"))).getValue("trips").jsonPrimitive.content.toInt())
    }

    @Test
    fun `list sessions clamps limit, parses dates and trims to budget`() = runBlocking {
        val a = obj(ListSessionsTool(ctx).execute(ListSessionsArgs(limit = 50, from = "2026-01-01", to = "bad")))
        assertEquals("sessions 1767225600000 9223372036854775807 20", analytics.requests.last())
        assertTrue(a.getValue("sessions").jsonArray.size in 1..20)
        assertEquals(30, a.getValue("sessions").jsonArray.first().jsonObject.getValue("min").jsonPrimitive.content.toInt())
    }

    @Test
    fun `numbers are rounded`() = runBlocking {
        val s = obj(SessionStatsTool(ctx).execute(SessionArgs(1)))
        val first = s.getValue("sensors").jsonArray.first().jsonObject
        assertEquals("1.2", first.getValue("min").jsonPrimitive.content)
        assertEquals("100.0", first.getValue("max").jsonPrimitive.content)
        assertTrue(s.getValue("truncated").jsonPrimitive.content.toBoolean())
        assertEquals("Перегрев", s.getValue("findings").jsonArray.single().jsonObject.getValue("title").jsonPrimitive.content)
    }

    @Test
    fun `sensor summary warm only and no data`() = runBlocking {
        val lt = obj(SensorSummaryTool(ctx).execute(SensorArgs("01.07", "year", warmOnly = true)))
        assertEquals("7.0", lt.getValue("avg").jsonPrimitive.content)
        assertEquals("sensor 01.07 ${NOW - 365 * DAY} true", analytics.requests.last())
        assertTrue(obj(SensorSummaryTool(ctx).execute(SensorArgs("01.99"))).containsKey("no_data"))
    }

    @Test
    fun `findings are computed on first use and problems come first`() = runBlocking {
        val f = obj(FindingsTool(ctx).execute(LimitArgs(5)))
        assertEquals(1, analytics.recomputes)
        val problems = f.getValue("problems").jsonArray
        assertEquals("critical", problems.first().jsonObject.getValue("severity").jsonPrimitive.content)
        assertTrue(problems.size <= 5)
        assertEquals(2, f.getValue("no_data").jsonArray.size)
        FindingsTool(ctx).execute(LimitArgs(5))
        assertEquals(1, analytics.recomputes)
    }

    @Test
    fun `dtc history groups events per code, newest first`() = runBlocking {
        val d = obj(DtcHistoryTool(ctx).execute(LimitArgs(3)))
        val codes = d.getValue("codes").jsonArray.map { it.jsonObject }
        assertEquals(listOf("P0101", "P0102", "P0103"), codes.map { it.getValue("code").jsonPrimitive.content })
        assertEquals(2, codes.first().getValue("events").jsonPrimitive.content.toInt())
        assertEquals(80, d.getValue("total_events").jsonPrimitive.content.toInt())
    }

    private val describer: suspend (Collection<String>, String?) -> Map<String, DtcInfo> = { codes, make ->
        codes.associateWith { code ->
            if (code == "P0101") {
                DtcInfo(code, null, DtcSource.OEM_UNKNOWN)
            } else {
                DtcInfo(
                    code, "Mass or Volume Air Flow Sensor Circuit Range/Performance for make $make", DtcSource.OBDEX,
                    causes = listOf("Dirty or contaminated MAF sensor element after an oiled air filter", "Air leak between the sensor and the throttle body", "Third"),
                    symptoms = listOf("Hesitation or stumbling on acceleration under load", "Second"),
                )
            }
        }
    }
    private val described = ToolContext(analytics, Cars(car), Trips(trips), clock = { NOW }, zone = { ZoneOffset.UTC }, log = { _, _, _ -> }, describeDtc = describer)

    @Test
    fun `dtc history carries title, causes and symptoms when few codes, within 2 KB`() = runBlocking {
        val raw = DtcHistoryTool(described).execute(LimitArgs(3))
        assertTrue(raw.toByteArray().size <= Compact.MAX_BYTES)
        val codes = obj(raw).getValue("codes").jsonArray.map { it.jsonObject }
        assertEquals(3, codes.size)
        val p0102 = codes.single { it.getValue("code").jsonPrimitive.content == "P0102" }
        assertEquals("generic", p0102.getValue("source").jsonPrimitive.content)
        assertEquals("Mass or Volume Air Flow Sensor Circuit Range/Performance for make Kia", p0102.getValue("title").jsonPrimitive.content)
        assertEquals(2, p0102.getValue("causes").jsonArray.size)
        assertEquals(1, p0102.getValue("symptoms").jsonArray.size)
        val p0101 = codes.single { it.getValue("code").jsonPrimitive.content == "P0101" }
        assertEquals("oem_unknown", p0101.getValue("source").jsonPrimitive.content)
        assertFalse(p0101.containsKey("title"))
    }

    @Test
    fun `dtc history drops details before dropping codes`() = runBlocking {
        val raw = DtcHistoryTool(described).execute(LimitArgs(30))
        assertTrue(raw.toByteArray().size <= Compact.MAX_BYTES)
        val codes = obj(raw).getValue("codes").jsonArray.map { it.jsonObject }
        assertTrue(codes.none { it.containsKey("symptoms") })
    }

    @Test
    fun `no active car means no data`() = runBlocking {
        val none = ToolContext(analytics, Cars(null), Trips(emptyList()), log = { _, _, _ -> })
        assertTrue(obj(DtcHistoryTool(none).execute(LimitArgs())).containsKey("no_data"))
    }

    @Test
    fun `toolbox registers eight read-only tools with labels and argument schemas`() {
        val box = VehicleDataToolbox(ctx)
        val tools = box.registry().tools
        assertEquals(setOf("carProfile", "tripStats", "listSessions", "sessionStats", "sensorSummary", "findings", "dtcHistory", "maintenanceDue"), tools.map { it.name }.toSet())
        assertEquals("ИИ смотрит ошибки…", box.progressLabel("dtcHistory"))
        val session = tools.single { it.name == "sessionStats" }.descriptor
        assertEquals(listOf("sessionId"), session.requiredParameters.map { it.name })
        val sensor = tools.single { it.name == "sensorSummary" }.descriptor
        assertTrue(sensor.optionalParameters.map { it.name }.containsAll(listOf("period", "warmOnly")))
    }
}

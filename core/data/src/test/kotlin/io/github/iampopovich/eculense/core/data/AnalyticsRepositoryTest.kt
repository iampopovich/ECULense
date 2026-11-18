package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.analytics.Evidence
import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.database.AnalyticsDao
import io.github.iampopovich.eculense.core.database.DtcDao
import io.github.iampopovich.eculense.core.database.DtcSnapshotEntity
import io.github.iampopovich.eculense.core.database.FindingDao
import io.github.iampopovich.eculense.core.database.FindingEntity
import io.github.iampopovich.eculense.core.database.ReadingPoint
import io.github.iampopovich.eculense.core.database.SensorAggregateRow
import io.github.iampopovich.eculense.core.database.SessionStartRow
import io.github.iampopovich.eculense.core.database.SessionSummaryRow
import io.github.iampopovich.eculense.core.model.CarProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** Таблица readings в памяти: SQL-агрегаты повторены простым Kotlin — проверяем логику репозитория, не SQLite. */
private class FakeAnalyticsDao : AnalyticsDao {
    data class Row(val id: Long, val sessionId: Long, val sensorId: String, val value: Double, val t: Long)
    val sessions = mutableListOf<SessionSummaryRow>()
    val carOf = mutableMapOf<Long, Long>()
    val rows = mutableListOf<Row>()

    fun add(sessionId: Long, carId: Long, start: Long, vararg readings: Triple<String, Double, Long>) {
        sessions += SessionSummaryRow(sessionId, start, start + 1000, "IMPORT_CARSCANNER", 0)
        carOf[sessionId] = carId
        readings.forEach { (s, v, t) -> rows += Row(rows.size + 1L, sessionId, s, v, t) }
    }

    private fun inCar(carId: Long, from: Long, to: Long) = sessions.filter { carOf[it.id] == carId && it.startedAtMs in from..to }.map { it.id }.toSet()
    private fun agg(id: String, list: List<Row>) = if (list.isEmpty()) null else
        SensorAggregateRow(id, "u", list.size, list.minOf { it.value }, list.maxOf { it.value }, list.map { it.value }.average(), list.map { it.sessionId }.distinct().size)

    override suspend fun sessions(carId: Long, fromMs: Long, toMs: Long, limit: Int) =
        sessions.filter { it.id in inCar(carId, fromMs, toMs) }.sortedByDescending { it.startedAtMs }.take(limit)
            .map { s -> s.copy(readings = rows.count { it.sessionId == s.id }) }
    override suspend fun sessionStarts(carId: Long) = sessions.filter { carOf[it.id] == carId }.sortedBy { it.startedAtMs }.map { SessionStartRow(it.id, it.startedAtMs, it.endedAtMs) }
    override suspend fun session(carId: Long, sessionId: Long) = sessions.firstOrNull { it.id == sessionId && carOf[it.id] == carId }
    override suspend fun sessionAggregates(sessionId: Long) = rows.filter { it.sessionId == sessionId }.groupBy { it.sensorId }.toSortedMap().mapNotNull { (id, l) -> agg(id, l) }
    override suspend fun sessionSample(sessionId: Long, sensorId: String, step: Int) = rows.filter { it.sessionId == sessionId && it.sensorId == sensorId && it.id % step == 0L }.map { it.value }
    override suspend fun sensorAggregate(carId: Long, sensorId: String, fromMs: Long, toMs: Long) = agg(sensorId, rows.filter { it.sessionId in inCar(carId, fromMs, toMs) && it.sensorId == sensorId })
    private fun warm(list: List<Row>) = list.filter { r -> rows.filter { it.sessionId == r.sessionId && it.sensorId == "01.05" && it.value >= 80 }.minOfOrNull { it.t }?.let { r.t >= it } == true }
    override suspend fun warmSensorAggregate(carId: Long, sensorId: String, fromMs: Long, toMs: Long) = agg(sensorId, warm(rows.filter { it.sessionId in inCar(carId, fromMs, toMs) && it.sensorId == sensorId }))
    override suspend fun sensorSample(carId: Long, sensorId: String, fromMs: Long, toMs: Long, step: Int) = rows.filter { it.sessionId in inCar(carId, fromMs, toMs) && it.sensorId == sensorId && it.id % step == 0L }.map { it.value }
    override suspend fun warmSensorSample(carId: Long, sensorId: String, fromMs: Long, toMs: Long, step: Int) = warm(rows.filter { it.sessionId in inCar(carId, fromMs, toMs) && it.sensorId == sensorId }).map { it.value }
    override suspend fun points(sessionId: Long, sensorIds: Collection<String>) = rows.filter { it.sessionId == sessionId && it.sensorId in sensorIds }.sortedBy { it.t }.map { ReadingPoint(it.sensorId, it.value, it.t) }
}

private class FakeFindingDao : FindingDao {
    val all = MutableStateFlow<List<FindingEntity>>(emptyList())
    private var next = 1L
    override fun observeForCar(carId: Long): Flow<List<FindingEntity>> = all.map { l -> l.filter { it.carId == carId } }
    override suspend fun forCar(carId: Long) = all.value.filter { it.carId == carId }
    override suspend fun byId(id: Long) = all.value.firstOrNull { it.id == id }
    override suspend fun deleteForCar(carId: Long) { all.value = all.value.filterNot { it.carId == carId } }
    override suspend fun insertAll(items: List<FindingEntity>) { all.value = all.value + items.map { it.copy(id = next++) } }
}

private class FakeDtcDao(val items: MutableList<DtcSnapshotEntity> = mutableListOf()) : DtcDao {
    override suspend fun insert(items: List<DtcSnapshotEntity>) { this.items += items }
    override suspend fun forCar(carId: Long) = items.filter { it.carId == carId }.sortedBy { it.takenAtMs }
    override suspend fun countEvents(code: String, fromMs: Long, toMs: Long) = items.count { it.code == code && it.takenAtMs in fromMs..toMs }
}

class AnalyticsRepositoryTest {
    private val dao = FakeAnalyticsDao()
    private val findings = FakeFindingDao()
    private val dtc = FakeDtcDao()
    private val cars = FakeCars()
    private val logs = mutableListOf<String>()

    private fun repo(dispatcher: TestDispatcher) =
        RoomAnalyticsRepository(dao, findings, dtc, cars, clock = { 1_000_000 }, zone = { ZoneOffset.UTC }, compute = dispatcher, log = { logs += it })

    @Test
    fun `recompute stores findings with evidence and reports progress and timing`() = runTest {
        // перегрев 112 °C 40 с в первой сессии, вторая — без ОЖ; чужая машина не учитывается
        dao.add(1, 1, 100, *(0..4).map { Triple("01.05", 112.0, it * 10_000L) }.toTypedArray())
        dao.add(2, 1, 200, Triple("01.0C", 800.0, 1))
        dao.add(3, 2, 300, Triple("01.05", 130.0, 1))
        dtc.items += DtcSnapshotEntity(takenAtMs = 10, vin = null, code = "P0107", kind = "STORED", carId = 1)
        dtc.items += DtcSnapshotEntity(takenAtMs = 10 + 86_400_000L * 5, vin = null, code = "P0107", kind = "STORED", carId = 1)
        val repo = repo(StandardTestDispatcher(testScheduler))

        val result = repo.recompute(1)

        assertEquals(Severity.CRITICAL, result.first().severity)
        val stored = repo.findings(1)
        val overheat = stored.single { it.finding.ruleId == "coolant-overheat" }.finding
        assertEquals(listOf(1L), overheat.sessionIds)
        assertTrue(overheat.evidence.contains(Evidence("Максимум ОЖ", "112,0 °C")))
        assertEquals(Severity.WARNING, stored.single { it.finding.ruleId == "dtc-air-sensors" }.finding.severity)
        val done = repo.state.value as AnalysisState.Done
        assertEquals(2, done.sessions)
        assertTrue(logs.single(), logs.single().startsWith("recompute car=1 sessions=2 cached=0"))

        // повторный пересчёт заменяет, а не дублирует
        repo.recompute(1)
        assertEquals(stored.size, repo.findings(1).size)
        assertEquals(stored.map { it.finding }, repo.findings(1).map { it.finding })
        // законченные сессии второй раз из БД не читаются
        assertTrue(logs.last(), logs.last().contains("cached=2 points=0"))
    }

    @Test
    fun `session report has per-sensor stats, p95 and session findings but no location`() = runTest {
        dao.add(1, 1, 100, *((1..100).map { Triple("01.0C", it.toDouble(), it * 1000L) } + Triple("gps.lat", 55.0, 1L)).toTypedArray())
        val report = repo(StandardTestDispatcher(testScheduler)).sessionReport(1, 1)!!
        assertEquals(listOf("01.0C"), report.sensors.map { it.sensorId })
        assertEquals(95.0, report.sensors.single().p95!!, 1e-9)
        assertEquals(100, report.session.readings) // координаты не считаются и не показываются
        assertTrue(report.findings.none { it.severity == Severity.OK || it.severity == Severity.NO_DATA })
        assertNull(repo(StandardTestDispatcher(testScheduler)).sessionReport(2, 1))
    }

    @Test
    fun `sensor summary warm only and location ids refused`() = runTest {
        dao.add(1, 1, 100, Triple("01.05", 40.0, 1), Triple("01.07", -8.6, 2), Triple("01.05", 85.0, 10), Triple("01.07", 12.5, 11))
        val repo = repo(StandardTestDispatcher(testScheduler))
        assertEquals(12.5, repo.sensorSummary(1, "01.07", 0, Long.MAX_VALUE, warmOnly = true)!!.mean, 1e-9)
        assertEquals(2, repo.sensorSummary(1, "01.07", 0, Long.MAX_VALUE, warmOnly = false)!!.count)
        assertNull(repo.sensorSummary(1, "gps.lat", 0, Long.MAX_VALUE, warmOnly = false))
    }

    @Test
    fun `maf rule sees the car profile engine volume`() = runTest {
        cars.all.value = listOf(CarProfile(id = 1, name = "c", brand = "Kia", year = 2005))
        dao.add(1, 1, 100, Triple("01.05", 90.0, 0))
        val repo = repo(StandardTestDispatcher(testScheduler))
        val maf = repo.recompute(1).single { it.ruleId == "maf-idle" }
        assertEquals(Severity.NO_DATA, maf.severity)
        assertTrue(maf.message.contains("Автомобиль"))
    }

    @Test
    fun `finding json round trip`() {
        val f = Finding("r", Severity.WARNING, "t", "m", listOf(Evidence("a", "1,0 \"В\"")), listOf(5, 6), 1, 2)
        val back = f.toEntity(1, 3).copy(id = 9).toModel()
        assertEquals(f, back.finding)
        assertEquals(9, back.id)
    }
}

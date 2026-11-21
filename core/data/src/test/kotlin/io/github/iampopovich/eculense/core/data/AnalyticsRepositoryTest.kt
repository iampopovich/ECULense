package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.database.ValueRow
import io.github.iampopovich.eculense.core.analytics.Evidence
import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.FindingArg
import io.github.iampopovich.eculense.core.analytics.FindingEnglish
import io.github.iampopovich.eculense.core.analytics.FindingKey
import io.github.iampopovich.eculense.core.analytics.FindingText
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** In-memory readings table: SQL aggregates reimplemented in plain Kotlin — we test the repository logic, not SQLite. */
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
    override suspend fun valuesPage(sessionId: Long, sensorId: String, afterTs: Long, afterId: Long, limit: Int) =
        rows.filter { it.sessionId == sessionId && it.sensorId == sensorId && (it.t > afterTs || (it.t == afterTs && it.id > afterId)) }
            .sortedWith(compareBy({ it.t }, { it.id })).take(limit).map { ValueRow(it.id, it.value, it.t) }
    override suspend fun warmStart(sessionId: Long, warmC: Double) = rows.filter { it.sessionId == sessionId && it.sensorId == "01.05" && it.value >= warmC }.minOfOrNull { it.t }
    override suspend fun sensorAggregate(carId: Long, sensorId: String, fromMs: Long, toMs: Long) = agg(sensorId, rows.filter { it.sessionId in inCar(carId, fromMs, toMs) && it.sensorId == sensorId })
    private fun warm(list: List<Row>, warmC: Double) = list.filter { r -> rows.filter { it.sessionId == r.sessionId && it.sensorId == "01.05" && it.value >= warmC }.minOfOrNull { it.t }?.let { r.t >= it } == true }
    override suspend fun warmSensorAggregate(carId: Long, sensorId: String, fromMs: Long, toMs: Long, warmC: Double) = agg(sensorId, warm(rows.filter { it.sessionId in inCar(carId, fromMs, toMs) && it.sensorId == sensorId }, warmC))
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
        // overheating 112 °C for 40 s in the first session, the second has no coolant temp; another car is not counted
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
        assertTrue(overheat.evidence.contains(Evidence(FindingText(FindingKey.EVIDENCE_COOLANT_MAX), FindingText(FindingKey.VALUE_CELSIUS, FindingArg.Num(112.0)))))
        // typed text survives the DB round trip; the text columns hold English for SQL readers
        assertFalse(overheat.isLegacyText)
        assertEquals(Severity.WARNING, stored.single { it.finding.ruleId == "dtc-air-sensors" }.finding.severity)
        val done = repo.state.value as AnalysisState.Done
        assertEquals(2, done.sessions)
        assertTrue(logs.single(), logs.single().startsWith("recompute car=1 sessions=2 cached=0"))

        // recomputing replaces rather than duplicates
        repo.recompute(1)
        assertEquals(stored.size, repo.findings(1).size)
        assertEquals(stored.map { it.finding }, repo.findings(1).map { it.finding })
        // finished sessions are not read from the DB a second time
        assertTrue(logs.last(), logs.last().contains("cached=2 points=0"))
    }

    @Test
    fun `session report has per-sensor stats, p95 and session findings but no location`() = runTest {
        dao.add(1, 1, 100, *((1..100).map { Triple("01.0C", it.toDouble(), it * 1000L) } + Triple("gps.lat", 55.0, 1L)).toTypedArray())
        val report = repo(StandardTestDispatcher(testScheduler)).sessionReport(1, 1)!!
        assertEquals(listOf("01.0C"), report.sensors.map { it.sensorId })
        assertEquals(95.0, report.sensors.single().p95!!, 1e-9)
        assertEquals(100, report.session.readings) // coordinates are neither computed nor shown
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
        assertEquals(FindingKey.MAF_NO_VOLUME, maf.message.first().key)
    }

    @Test
    fun `session digests for a period reuse the cache and skip other cars and periods`() = runTest {
        dao.add(1, 1, 100, Triple("01.05", 90.0, 1), Triple("01.05", 92.0, 2))
        dao.add(2, 1, 5_000, Triple("01.05", 95.0, 1))
        dao.add(3, 2, 200, Triple("01.05", 130.0, 1))
        val repo = repo(StandardTestDispatcher(testScheduler))
        val first = repo.sessionDigests(1, 0, 1_000)
        assertEquals(listOf(1L), first.map { it.sessionId })
        assertEquals(92.0, first.single().coolant.max, 1e-9)
        assertTrue(logs.last(), logs.last().startsWith("digests car=1 sessions=1 computed=1"))
        repo.sessionDigests(1, 0, Long.MAX_VALUE)
        assertTrue(logs.last(), logs.last().startsWith("digests car=1 sessions=2 computed=1"))
    }

    @Test
    fun `finding json round trip`() {
        val f = Finding(
            "r", Severity.WARNING, FindingText(FindingKey.TITLE_DTC_MIXTURE, FindingArg.Raw("P0171")),
            listOf(
                FindingText(FindingKey.LTFT_OUT, FindingArg.Num(10.0, 0), FindingArg.Join(listOf(FindingText(FindingKey.BANK_PERCENT, FindingArg.Count(1), FindingArg.Num(12.5, signed = true))))),
                FindingText(FindingKey.MIXTURE_LEAN, FindingArg.Text(null)),
            ),
            listOf(
                Evidence(FindingText.raw("P0171"), FindingText(FindingKey.DTC_OCCURRENCES, FindingArg.Count(2), FindingArg.DateRange(1, 2))),
                Evidence(FindingText(FindingKey.EVIDENCE_SOURCE), FindingText(FindingKey.VALUE_PLAIN, FindingArg.Sensor("elm.voltage"))),
                Evidence(FindingText(FindingKey.EVIDENCE_FIRST_NONZERO), FindingText(FindingKey.KM_ON_DATE, FindingArg.Num(5.0, 0), FindingArg.Date(7))),
            ),
            listOf(5, 6), 1, 2,
        )
        val entity = f.toEntity(1, 3).copy(id = 9)
        assertEquals("Mixture (P0171)", entity.title)
        val back = entity.toModel()
        assertEquals(f, back.finding)
        assertEquals(9, back.id)
    }

    @Test
    fun `rows stored before localization stay readable as raw text`() {
        val old = FindingEntity(
            id = 4, carId = 1, ruleId = "coolant-overheat", severity = "OK", title = "Перегрев двигателя", message = "Перегрева нет.",
            evidenceJson = """{"evidence":[{"label":"Максимум ОЖ","value":"98,0 °C"}],"sessions":[3]}""", periodFromMs = null, periodToMs = null, createdAtMs = 5,
        )
        val f = old.toModel().finding
        assertTrue(f.isLegacyText)
        assertEquals("Перегрев двигателя", FindingEnglish.title(f))
        assertEquals("Перегрева нет.", FindingEnglish.message(f))
        assertEquals(mapOf("Максимум ОЖ" to "98,0 °C"), FindingEnglish.evidence(f))
        assertEquals(listOf(3L), f.sessionIds)
        // an unknown key (e.g. from a newer version) falls back to the stored text instead of failing
        val unknown = old.copy(evidenceJson = """{"v":2,"title":{"k":"NO_SUCH_KEY"},"message":[],"sessions":[]}""").toModel().finding
        assertTrue(unknown.isLegacyText)
    }

    @Test
    fun `legacy rows are regenerated in typed form once and none is lost`() = runTest {
        dao.add(1, 1, 100, *(0..4).map { Triple("01.05", 112.0, it * 10_000L) }.toTypedArray())
        fun legacy(rule: String) = FindingEntity(
            carId = 1, ruleId = rule, severity = "WARNING", title = "Старый текст", message = "Старое сообщение",
            evidenceJson = """{"evidence":[],"sessions":[1]}""", periodFromMs = null, periodToMs = null, createdAtMs = 5,
        )
        findings.insertAll(listOf(legacy("coolant-overheat"), legacy("rule-of-an-older-version")))
        val oldId = findings.all.value.first { it.ruleId == "coolant-overheat" }.id
        val repo = repo(StandardTestDispatcher(testScheduler))

        // opened by its old id (e.g. from a notification): the regenerated finding of the same rule comes back
        val opened = repo.finding(oldId)!!
        assertEquals("coolant-overheat", opened.finding.ruleId)
        assertFalse(opened.finding.isLegacyText)
        assertEquals(Severity.CRITICAL, opened.finding.severity)

        val stored = repo.findings(1)
        assertTrue(stored.filter { it.finding.ruleId != "rule-of-an-older-version" }.none { it.finding.isLegacyText })
        // a rule that produced nothing new keeps its stored text instead of disappearing
        assertEquals("Старый текст", FindingEnglish.title(stored.single { it.finding.ruleId == "rule-of-an-older-version" }.finding))
        // only one recompute: the kept legacy row does not trigger another one
        repo.findings(1)
        assertEquals(1, logs.count { it.startsWith("recompute") })
    }
}

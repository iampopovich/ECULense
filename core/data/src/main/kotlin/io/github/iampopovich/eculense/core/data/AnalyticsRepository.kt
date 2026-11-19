package io.github.iampopovich.eculense.core.data

import android.util.Log
import io.github.iampopovich.eculense.core.analytics.AnalysisInput
import io.github.iampopovich.eculense.core.analytics.AnalyticsEngine
import io.github.iampopovich.eculense.core.analytics.DtcEvent
import io.github.iampopovich.eculense.core.analytics.DtcRule
import io.github.iampopovich.eculense.core.analytics.Evidence
import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.Percentiles
import io.github.iampopovich.eculense.core.analytics.SensorIds
import io.github.iampopovich.eculense.core.analytics.SessionDigest
import io.github.iampopovich.eculense.core.analytics.SessionDigester
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.database.AnalyticsDao
import io.github.iampopovich.eculense.core.database.DtcDao
import io.github.iampopovich.eculense.core.database.FindingDao
import io.github.iampopovich.eculense.core.database.FindingEntity
import io.github.iampopovich.eculense.core.database.SensorAggregateRow
import io.github.iampopovich.eculense.core.database.SessionStartRow
import io.github.iampopovich.eculense.core.database.SessionSummaryRow
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.SessionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.ZoneId

data class StoredFinding(val id: Long, val carId: Long, val finding: Finding, val createdAtMs: Long)

/** Recompute state — shared by the «Анализ» screen and the post-import recompute. */
sealed interface AnalysisState {
    data object Idle : AnalysisState
    data class Running(val done: Int, val total: Int) : AnalysisState
    data class Done(val atMs: Long, val durationMs: Long, val sessions: Int) : AnalysisState
    data class Failed(val message: String) : AnalysisState
}

data class SessionSummary(val id: Long, val startedAtMs: Long, val endedAtMs: Long?, val source: SessionSource, val readings: Int)

/** Sensor summary: min/max/mean/count from SQL, percentiles from a decimated sample. */
data class SensorSummary(
    val sensorId: String,
    val unit: String,
    val count: Int,
    val sessions: Int,
    val min: Double,
    val max: Double,
    val mean: Double,
    val median: Double?,
    val p95: Double?,
)

data class SessionReport(val session: SessionSummary, val sensors: List<SensorSummary>, val findings: List<Finding>)

/**
 * Analytics over local data (stage 3): recomputing findings and data for AI tools.
 * Everything is read-only except the findings table. The code computes the numbers — the model only explains them.
 */
interface AnalyticsRepository {
    val state: StateFlow<AnalysisState>
    fun observeFindings(carId: Long): Flow<List<StoredFinding>>
    suspend fun findings(carId: Long): List<StoredFinding>
    suspend fun finding(id: Long): StoredFinding?
    /** Recompute the car's findings over its entire history; progress goes to [state]. */
    suspend fun recompute(carId: Long): List<Finding>
    suspend fun sessions(carId: Long, fromMs: Long = 0, toMs: Long = Long.MAX_VALUE, limit: Int = 20): List<SessionSummary>
    suspend fun sessionReport(carId: Long, sessionId: Long): SessionReport?
    suspend fun sensorSummary(carId: Long, sensorId: String, fromMs: Long, toMs: Long, warmOnly: Boolean): SensorSummary?
    suspend fun dtcEvents(carId: Long): List<DtcEvent>
    /**
     * Digests of the car's sessions that started in [fromMs]..[toMs] (trends): from the process cache or streamed —
     * one session at a time, without loading all values into memory.
     */
    suspend fun sessionDigests(carId: Long, fromMs: Long, toMs: Long): List<SessionDigest> = emptyList()
}

internal class RoomAnalyticsRepository(
    private val dao: AnalyticsDao,
    private val findingDao: FindingDao,
    private val dtcDao: DtcDao,
    private val cars: CarRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val log: (String) -> Unit = { msg -> runCatching { Log.i(TAG, msg) } },
) : AnalyticsRepository {
    private val _state = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    override val state: StateFlow<AnalysisState> = _state.asStateFlow()
    private val mutex = Mutex()
    /**
     * Digests of finished sessions never change — keep them in process memory: a repeated recompute
     * (button, import, profile edit) reads only new recordings from the DB. Key is the id (AUTOINCREMENT is never reused).
     */
    private val digestCache = java.util.concurrent.ConcurrentHashMap<Long, SessionDigest>()

    override fun observeFindings(carId: Long): Flow<List<StoredFinding>> = findingDao.observeForCar(carId).map { list -> list.map { it.toModel() } }

    override suspend fun findings(carId: Long): List<StoredFinding> = findingDao.forCar(carId).map { it.toModel() }

    override suspend fun finding(id: Long): StoredFinding? = findingDao.byId(id)?.toModel()

    override suspend fun recompute(carId: Long): List<Finding> = withContext(compute) { mutex.withLock { recomputeLocked(carId) } }

    private suspend fun recomputeLocked(carId: Long): List<Finding> {
        val started = System.nanoTime()
        return try {
            val sessions = dao.sessionStarts(carId)
            _state.value = AnalysisState.Running(0, sessions.size)
            var points = 0L
            var cached = 0
            val digests = sessions.mapIndexed { i, s ->
                val digest = digestCache[s.id]?.also { cached++ } ?: digestOf(s).also { points += it.second }.first
                _state.value = AnalysisState.Running(i + 1, sessions.size)
                digest
            }
            val input = AnalysisInput(cars.cars().firstOrNull { it.id == carId }, digests, dtcEvents(carId), clock(), zone())
            val findings = withContext(compute) { AnalyticsEngine.evaluate(input) }
            val now = clock()
            findingDao.replaceForCar(carId, findings.map { it.toEntity(carId, now) })
            val ms = (System.nanoTime() - started) / 1_000_000
            log("recompute car=$carId sessions=${sessions.size} cached=$cached points=$points findings=${findings.count { it.severity.rank >= Severity.INFO.rank }} ms=$ms")
            _state.value = AnalysisState.Done(now, ms, sessions.size)
            findings
        } catch (e: CancellationException) {
            _state.value = AnalysisState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = AnalysisState.Failed(e.message ?: e::class.simpleName.orEmpty())
            throw e
        }
    }

    /** One session: its values are loaded from the DB, folded into a digest and released; a finished one goes to the cache. */
    private suspend fun digestOf(s: SessionStartRow): Pair<SessionDigest, Int> {
        val rows = dao.points(s.id, SensorIds.FOR_RULES)
        val digest = withContext(compute) {
            val acc = SessionDigester.Accumulator()
            rows.forEach { acc.accept(it.sensorId, it.value, it.timestampMs) }
            acc.build(s.id, s.startedAtMs)
        }
        if (s.endedAtMs != null) digestCache[s.id] = digest
        return digest to rows.size
    }

    override suspend fun sessionDigests(carId: Long, fromMs: Long, toMs: Long): List<SessionDigest> = withContext(compute) {
        val started = System.nanoTime()
        var computed = 0
        val list = dao.sessionStarts(carId).filter { it.startedAtMs in fromMs..toMs }.map { s ->
            digestCache[s.id] ?: digestOf(s).first.also { computed++ }
        }
        log("digests car=$carId sessions=${list.size} computed=$computed ms=${(System.nanoTime() - started) / 1_000_000}")
        list
    }

    override suspend fun sessions(carId: Long, fromMs: Long, toMs: Long, limit: Int): List<SessionSummary> =
        dao.sessions(carId, fromMs, toMs, limit).map { it.toModel() }

    override suspend fun sessionReport(carId: Long, sessionId: Long): SessionReport? {
        val row = dao.session(carId, sessionId) ?: return null
        val aggregates = dao.sessionAggregates(sessionId).filterNot { SensorIds.isLocation(it.sensorId) }
        val sensors = aggregates.map { a -> a.toSummary(sample = dao.sessionSample(sessionId, a.sensorId, Percentiles.step(a.count))) }
        val points = dao.points(sessionId, SensorIds.FOR_RULES)
        val digest: SessionDigest = withContext(compute) {
            SessionDigester.digest(sessionId, row.startedAtMs, points.map { SensorReading(it.sensorId, it.value, "", it.timestampMs) })
        }
        val car = cars.cars().firstOrNull { it.id == carId }
        val rules = AnalyticsEngine.RULES.filter { it != DtcRule }
        val findings = AnalyticsEngine.evaluate(AnalysisInput(car, listOf(digest), emptyList(), clock(), zone()), rules)
            .filter { it.severity.rank >= Severity.INFO.rank }
        return SessionReport(row.copy(readings = aggregates.sumOf { it.count }).toModel(), sensors, findings)
    }

    override suspend fun sensorSummary(carId: Long, sensorId: String, fromMs: Long, toMs: Long, warmOnly: Boolean): SensorSummary? {
        if (SensorIds.isLocation(sensorId)) return null
        val agg = (if (warmOnly) dao.warmSensorAggregate(carId, sensorId, fromMs, toMs) else dao.sensorAggregate(carId, sensorId, fromMs, toMs))
            ?: return null
        val step = Percentiles.step(agg.count)
        val sample = if (warmOnly) dao.warmSensorSample(carId, sensorId, fromMs, toMs, step) else dao.sensorSample(carId, sensorId, fromMs, toMs, step)
        return agg.toSummary(sample)
    }

    override suspend fun dtcEvents(carId: Long): List<DtcEvent> =
        dtcDao.forCar(carId).map { DtcEvent(it.code, runCatching { DtcKind.valueOf(it.kind) }.getOrDefault(DtcKind.STORED), it.takenAtMs) }

    private fun SensorAggregateRow.toSummary(sample: List<Double>) = SensorSummary(
        sensorId = sensorId, unit = unit, count = count, sessions = sessions, min = min, max = max, mean = mean,
        median = Percentiles.of(sample, 0.5), p95 = Percentiles.of(sample, 0.95),
    )

    private fun SessionSummaryRow.toModel() = SessionSummary(
        id, startedAtMs, endedAtMs, runCatching { SessionSource.valueOf(source) }.getOrDefault(SessionSource.LIVE), readings,
    )

    companion object {
        const val TAG = "EcuAnalytics"
    }
}

/** Evidence is stored as JSON: {"evidence":[{"label","value"}],"sessions":[…]}. */
internal object FindingJson {
    fun encode(f: Finding): String = buildJsonObject {
        put("evidence", JsonArray(f.evidence.map { e -> buildJsonObject { put("label", e.label); put("value", e.value) } }))
        put("sessions", JsonArray(f.sessionIds.map { JsonPrimitive(it) }))
    }.toString()

    fun decode(json: String): Pair<List<Evidence>, List<Long>> = runCatching {
        val o = Json.parseToJsonElement(json).jsonObject
        val evidence = o["evidence"]?.jsonArray?.map { e ->
            val eo = e.jsonObject
            Evidence(eo.getValue("label").jsonPrimitive.content, eo.getValue("value").jsonPrimitive.content)
        }.orEmpty()
        val sessions = o["sessions"]?.jsonArray?.map { it.jsonPrimitive.long }.orEmpty()
        evidence to sessions
    }.getOrDefault(emptyList<Evidence>() to emptyList())
}

internal fun Finding.toEntity(carId: Long, now: Long) = FindingEntity(
    carId = carId, ruleId = ruleId, severity = severity.name, title = title, message = message,
    evidenceJson = FindingJson.encode(this), periodFromMs = fromMs, periodToMs = toMs, createdAtMs = now,
)

internal fun FindingEntity.toModel(): StoredFinding {
    val (evidence, sessions) = FindingJson.decode(evidenceJson)
    return StoredFinding(
        id, carId,
        Finding(ruleId, runCatching { Severity.valueOf(severity) }.getOrDefault(Severity.INFO), title, message, evidence, sessions, periodFromMs, periodToMs),
        createdAtMs,
    )
}

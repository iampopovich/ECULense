package io.github.iampopovich.eculense.core.data

import android.util.Log
import io.github.iampopovich.eculense.core.analytics.AnalysisInput
import io.github.iampopovich.eculense.core.analytics.AnalyticsEngine
import io.github.iampopovich.eculense.core.analytics.DtcEvent
import io.github.iampopovich.eculense.core.analytics.DtcRule
import io.github.iampopovich.eculense.core.analytics.Evidence
import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.FindingArg
import io.github.iampopovich.eculense.core.analytics.FindingEnglish
import io.github.iampopovich.eculense.core.analytics.FindingKey
import io.github.iampopovich.eculense.core.analytics.FindingText
import io.github.iampopovich.eculense.core.analytics.Percentiles
import io.github.iampopovich.eculense.core.analytics.SensorIds
import io.github.iampopovich.eculense.core.analytics.SessionDigest
import io.github.iampopovich.eculense.core.analytics.SessionDigester
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.analytics.TripEventFact
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.ZoneId

data class StoredFinding(val id: Long, val carId: Long, val finding: Finding, val createdAtMs: Long)

/** Recompute state — shared by the «Analysis» screen and the post-import recompute. */
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
    /** B10: stalls and marks stored with the car's recordings. */
    private val tripEvents: suspend (carId: Long) -> List<TripEventFact> = { emptyList() },
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
            val input = AnalysisInput(cars.cars().firstOrNull { it.id == carId }, digests, dtcEvents(carId), clock(), zone(), runCatching { tripEvents(carId) }.getOrDefault(emptyList()))
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
        dtcDao.forCar(carId).map {
            DtcEvent(it.code, runCatching { DtcKind.valueOf(it.kind) }.getOrDefault(DtcKind.STORED), it.takenAtMs, DiagCodec.decodeFreezeFrame(it.freezeFrame, it.takenAtMs))
        }

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

/**
 * Finding payload in the evidenceJson column. Version 2 (localization) stores the typed text:
 * {"v":2,"title":T,"message":[T…],"evidence":[{"label":T,"value":T}],"sessions":[…]}, T = {"k":"KEY","a":[arg…]}.
 * The title/message columns then hold the English rendering (readable in SQL, fallback when a key is unknown).
 * Version 1 rows (before localization) have {"evidence":[{"label","value"}],"sessions":[…]} with Russian strings: they are
 * read as raw text ([Finding.isLegacyText]) until the next recompute replaces them. No schema change is needed:
 * findings are derived data, recomputed from recordings.
 */
internal object FindingJson {
    private const val VERSION = 2

    fun encode(f: Finding): String = buildJsonObject {
        put("v", VERSION)
        put("title", text(f.title))
        put("message", JsonArray(f.message.map(::text)))
        put("evidence", JsonArray(f.evidence.map { e -> buildJsonObject { put("label", text(e.label)); put("value", text(e.value)) } }))
        put("sessions", JsonArray(f.sessionIds.map { JsonPrimitive(it) }))
    }.toString()

    /** Typed parts of a stored finding; null title means "use the text columns" (v1 row or unknown key). */
    data class Decoded(val title: FindingText?, val message: List<FindingText>?, val evidence: List<Evidence>, val sessions: List<Long>)

    fun decode(json: String): Decoded = runCatching {
        val o = Json.parseToJsonElement(json).jsonObject
        val sessions = o["sessions"]?.jsonArray?.map { it.jsonPrimitive.long }.orEmpty()
        val v = o["v"]?.jsonPrimitive?.intOrNull ?: 1
        if (v >= 2) {
            runCatching {
                Decoded(
                    title = text(o.getValue("title")),
                    message = o.getValue("message").jsonArray.map(::text),
                    evidence = o["evidence"]?.jsonArray?.map { e -> Evidence(text(e.jsonObject.getValue("label")), text(e.jsonObject.getValue("value"))) }.orEmpty(),
                    sessions = sessions,
                )
            }.getOrElse { Decoded(null, null, emptyList(), sessions) }
        } else {
            val evidence = o["evidence"]?.jsonArray?.map { e ->
                val eo = e.jsonObject
                Evidence(FindingText.raw(eo.getValue("label").jsonPrimitive.content), FindingText.raw(eo.getValue("value").jsonPrimitive.content))
            }.orEmpty()
            Decoded(null, null, evidence, sessions)
        }
    }.getOrDefault(Decoded(null, null, emptyList(), emptyList()))

    private fun text(t: FindingText): JsonObject = buildJsonObject {
        put("k", t.key.name)
        if (t.args.isNotEmpty()) put("a", JsonArray(t.args.map(::arg)))
    }

    private fun arg(a: FindingArg): JsonObject = buildJsonObject {
        when (a) {
            is FindingArg.Num -> { put("t", "n"); put("v", a.value); put("d", a.decimals); if (a.signed) put("s", true) }
            is FindingArg.Count -> { put("t", "i"); put("v", a.n) }
            is FindingArg.Date -> { put("t", "date"); put("v", a.ms) }
            is FindingArg.DateRange -> { put("t", "range"); put("f", a.fromMs); put("to", a.toMs) }
            is FindingArg.Sensor -> { put("t", "sensor"); put("v", a.id) }
            is FindingArg.Raw -> { put("t", "raw"); put("v", a.text) }
            is FindingArg.Text -> { put("t", "text"); a.text?.let { put("v", text(it)) } }
            is FindingArg.Join -> { put("t", "join"); put("v", JsonArray(a.items.map(::text))); put("sep", a.separator) }
        }
    }

    private fun text(e: JsonElement): FindingText {
        val o = e.jsonObject
        return FindingText(FindingKey.valueOf(o.getValue("k").jsonPrimitive.content), o["a"]?.jsonArray?.map(::arg).orEmpty())
    }

    private fun arg(e: JsonElement): FindingArg {
        val o = e.jsonObject
        fun p(name: String) = o.getValue(name).jsonPrimitive
        return when (val type = p("t").content) {
            "n" -> FindingArg.Num(p("v").double, p("d").int, o["s"]?.jsonPrimitive?.booleanOrNull ?: false)
            "i" -> FindingArg.Count(p("v").int)
            "date" -> FindingArg.Date(p("v").long)
            "range" -> FindingArg.DateRange(p("f").long, p("to").long)
            "sensor" -> FindingArg.Sensor(p("v").content)
            "raw" -> FindingArg.Raw(p("v").content)
            "text" -> FindingArg.Text(o["v"]?.let(::text))
            "join" -> FindingArg.Join(o.getValue("v").jsonArray.map(::text), p("sep").content)
            else -> error("unknown finding argument type $type")
        }
    }
}

internal fun Finding.toEntity(carId: Long, now: Long) = FindingEntity(
    carId = carId, ruleId = ruleId, severity = severity.name, title = FindingEnglish.title(this), message = FindingEnglish.message(this),
    evidenceJson = FindingJson.encode(this), periodFromMs = fromMs, periodToMs = toMs, createdAtMs = now,
)

internal fun FindingEntity.toModel(): StoredFinding {
    val d = FindingJson.decode(evidenceJson)
    val title = d.title ?: FindingText.raw(title)
    val message = d.message ?: listOf(FindingText.raw(message))
    return StoredFinding(
        id, carId,
        Finding(ruleId, runCatching { Severity.valueOf(severity) }.getOrDefault(Severity.INFO), title, message, d.evidence, d.sessions, periodFromMs, periodToMs),
        createdAtMs,
    )
}

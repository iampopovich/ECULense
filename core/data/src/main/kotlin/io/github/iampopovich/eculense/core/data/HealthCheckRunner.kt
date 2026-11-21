package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.analytics.CarNorms
import io.github.iampopovich.eculense.core.analytics.EngineTraits
import io.github.iampopovich.eculense.core.analytics.HealthCheck
import io.github.iampopovich.eculense.core.analytics.Norm
import io.github.iampopovich.eculense.core.analytics.NormKey
import io.github.iampopovich.eculense.core.analytics.NormSource
import io.github.iampopovich.eculense.core.analytics.HealthInput
import io.github.iampopovich.eculense.core.analytics.HealthItem
import io.github.iampopovich.eculense.core.analytics.HealthItemId
import io.github.iampopovich.eculense.core.analytics.HealthNote
import io.github.iampopovich.eculense.core.analytics.HealthResult
import io.github.iampopovich.eculense.core.analytics.HealthStatus
import io.github.iampopovich.eculense.core.analytics.SensorIds
import io.github.iampopovich.eculense.core.analytics.Thresholds
import io.github.iampopovich.eculense.core.database.HealthCheckEntity
import io.github.iampopovich.eculense.core.database.HealthDao
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.FreezeFrame
import io.github.iampopovich.eculense.core.obd.ClearedCounters
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.ObdDiagnostics
import io.github.iampopovich.eculense.core.obd.Readiness
import io.github.iampopovich.eculense.core.obd.StandardPids
import io.github.iampopovich.eculense.core.obd.UsedCarCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/** One stored quick check (B6). [id] = 0 for a check that was not stored (simulated link). No VIN, no location. */
data class HealthCheckRecord(
    val id: Long,
    val carId: Long?,
    val takenAtMs: Long,
    val result: HealthResult,
    val dtcs: List<Dtc>,
    val freezeFrame: FreezeFrame?,
    val readiness: Readiness?,
    val counters: ClearedCounters?,
    val odometerKm: Long?,
)

interface HealthRepository {
    fun observe(carId: Long?): Flow<List<HealthCheckRecord>>
    suspend fun byId(id: Long): HealthCheckRecord?
    suspend fun save(record: HealthCheckRecord): Long
}

/**
 * Text forms of the health check columns: items "ID=STATUS|value|NOTE|norm;...", codes "P0133:STORED;...".
 * norm (R-H) = "KEY~low~high~SOURCE~sessions" (empty when the item was not compared with a norm; older rows lack it).
 */
object HealthCodec {
    fun encodeItems(items: List<HealthItem>): String =
        items.joinToString(";") { "${it.id.name}=${it.status.name}|${it.value ?: ""}|${it.note.name}|${it.norm?.let(::encodeNorm).orEmpty()}" }

    private fun encodeNorm(n: Norm) = listOf(n.key.name, n.low?.toString().orEmpty(), n.high?.toString().orEmpty(), n.source.name, n.sampleCount.toString()).joinToString("~")

    private fun decodeNorm(text: String?): Norm? {
        val p = text?.split('~')?.takeIf { it.size == 5 } ?: return null
        val key = NormKey.entries.firstOrNull { it.name == p[0] } ?: return null
        val source = NormSource.entries.firstOrNull { it.name == p[3] } ?: return null
        return Norm(key, p[1].toDoubleOrNull(), p[2].toDoubleOrNull(), source, sampleCount = p[4].toIntOrNull() ?: 0)
    }

    fun decodeItems(text: String): List<HealthItem> = text.split(';').mapNotNull { part ->
        val (id, rest) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val f = rest.split('|')
        val itemId = HealthItemId.entries.firstOrNull { it.name == id } ?: return@mapNotNull null
        val status = HealthStatus.entries.firstOrNull { it.name == f.getOrNull(0) } ?: return@mapNotNull null
        HealthItem(itemId, status, f.getOrNull(1)?.toDoubleOrNull(), HealthNote.entries.firstOrNull { it.name == f.getOrNull(2) } ?: HealthNote.NONE, decodeNorm(f.getOrNull(3)))
    }

    fun encodeDtcs(dtcs: List<Dtc>): String = dtcs.joinToString(";") { "${it.code}:${it.kind.name}" }

    fun decodeDtcs(text: String?): List<Dtc> = text.orEmpty().split(';').mapNotNull { p ->
        val (code, kind) = p.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        DtcKind.entries.firstOrNull { it.name == kind }?.let { Dtc(code, it) }
    }
}

internal class RoomHealthRepository(private val dao: HealthDao) : HealthRepository {
    override fun observe(carId: Long?): Flow<List<HealthCheckRecord>> = dao.observeForCar(carId).map { l -> l.map { it.toModel() } }
    override suspend fun byId(id: Long): HealthCheckRecord? = dao.byId(id)?.toModel()

    override suspend fun save(record: HealthCheckRecord): Long = dao.insert(
        HealthCheckEntity(
            carId = record.carId, takenAtMs = record.takenAtMs, verdict = record.result.verdict.name,
            items = HealthCodec.encodeItems(record.result.items),
            // The freeze frame rides with the codes on a second line (one column, no schema change for it).
            dtcs = (HealthCodec.encodeDtcs(record.dtcs) + (record.freezeFrame?.let { "\n" + DiagCodec.encode(it) } ?: "")).ifEmpty { null },
            readiness = record.readiness?.let(DiagCodec::encode),
            counters = record.counters?.let(DiagCodec::encode),
            odometerKm = record.odometerKm,
        ),
    )

    private fun HealthCheckEntity.toModel(): HealthCheckRecord {
        val lines = dtcs.orEmpty().split('\n', limit = 2)
        val decodedItems = HealthCodec.decodeItems(items)
        val codes = HealthCodec.decodeDtcs(lines.firstOrNull())
        val mil = decodedItems.firstOrNull { it.id == HealthItemId.CHECK_ENGINE }?.status?.let { it == HealthStatus.FAULT }
        val readinessDecoded = DiagCodec.decodeReadiness(readiness, mil, codes.count { it.kind == DtcKind.STORED })
        return HealthCheckRecord(
            id, carId, takenAtMs,
            HealthResult(HealthStatus.entries.firstOrNull { it.name == verdict } ?: HealthStatus.NOT_SUPPORTED, decodedItems),
            codes, DiagCodec.decodeFreezeFrame(lines.getOrNull(1), takenAtMs),
            readinessDecoded, DiagCodec.decodeCounters(counters), odometerKm,
        )
    }
}

/** Where a running check is. */
sealed interface HealthProgress {
    data object Idle : HealthProgress
    data object NotConnected : HealthProgress
    data object ReadingCodes : HealthProgress
    data class Live(val secondsLeft: Int) : HealthProgress
    /** [stored] = false for a simulated link: shown, not kept. */
    data class Done(val record: HealthCheckRecord, val stored: Boolean) : HealthProgress
    data class Failed(val message: String) : HealthProgress
}

/**
 * B6 «Quick health check»: codes (03/07/0A), readiness + counters, freeze frame, then [liveMs] of live data (owner
 * [OWNER]), [HealthCheck] rules, stored for the active car unless the link is simulated ([LiveWriteGate]).
 */
class HealthCheckRunner internal constructor(
    private val controller: ConnectionController,
    private val repository: HealthRepository,
    private val gate: LiveWriteGate,
    private val activeCarId: suspend () -> Long?,
    private val odometerKm: suspend (carId: Long) -> Long?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val liveMs: Long = Thresholds.HEALTH_LIVE_MS,
    /** R-H: the stored norms of a real car (null = generic only). */
    private val carNorms: suspend (carId: Long) -> CarNorms? = { null },
) {
    private val _progress = MutableStateFlow<HealthProgress>(HealthProgress.Idle)
    val progress: StateFlow<HealthProgress> = _progress.asStateFlow()

    suspend fun run(): HealthProgress {
        val connected = controller.state.value as? ConnectionState.Connected
            ?: return HealthProgress.NotConnected.also { _progress.value = it }
        return try {
            _progress.value = HealthProgress.ReadingCodes
            val snapshot = controller.withSession { s ->
                val diag = ObdDiagnostics(s, clock)
                val stored = runCatching { s.readDtcs(DtcKind.STORED) }.getOrNull()
                val pending = runCatching { s.readDtcs(DtcKind.PENDING) }.getOrNull()
                val permanent = if (s.isCanBus) runCatching { s.readDtcs(DtcKind.PERMANENT) }.getOrNull() else null
                val readiness = runCatching { diag.readiness() }.getOrNull()
                val counters = runCatching { diag.counters(connected.supportedPids) }.getOrNull()
                val frame = if (!stored.isNullOrEmpty()) runCatching { diag.freezeFrame() }.getOrNull() else null
                Snapshot(stored, pending, permanent, s.isCanBus, readiness, counters, frame)
            } ?: return HealthProgress.NotConnected.also { _progress.value = it }
            val samples = live(connected.supportedPids)
            val store = gate.allowed()
            val car = if (store) activeCarId() else null
            // Traits from this ECU first (a demo car is judged by its own traits), the car's learned/user norms only for a real link.
            val liveTraits = runCatching { controller.detectTraits(connected.supportedPids, clock()) }.getOrDefault(EngineTraits())
            val norms = car?.let { runCatching { carNorms(it) }.getOrNull() }?.let { it.copy(traits = liveTraits.orElse(it.traits)) }
                ?: CarNorms(traits = liveTraits)
            val clear = snapshot.counters?.takeIf { !it.isEmpty }?.let { UsedCarCheck.evaluate(it, snapshot.readiness) }
            val result = HealthCheck.evaluate(
                HealthInput(
                    stored = snapshot.stored?.map { it.code }, pending = snapshot.pending?.map { it.code },
                    permanent = snapshot.permanent?.map { it.code }, permanentSupported = snapshot.can,
                    milOn = snapshot.readiness?.milOn, supportedMonitors = snapshot.readiness?.supported?.size,
                    incompleteMonitors = snapshot.readiness?.incompleteCount, lowClearCounters = clear?.lowCounters,
                    samples = samples,
                ),
                norms,
            )
            // A simulated link is shown, not kept, and never tied to the real active car (no name, mileage or findings).
            val record = HealthCheckRecord(
                id = 0, carId = car, takenAtMs = clock(), result = result,
                dtcs = listOfNotNull(snapshot.stored, snapshot.pending, snapshot.permanent).flatten().distinct(),
                freezeFrame = snapshot.frame, readiness = snapshot.readiness, counters = snapshot.counters,
                odometerKm = car?.let { runCatching { odometerKm(it) }.getOrNull() },
            )
            val saved = if (store) record.copy(id = repository.save(record)) else record
            HealthProgress.Done(saved, store).also { _progress.value = it }
        } catch (e: CancellationException) {
            _progress.value = HealthProgress.Idle
            throw e
        } catch (e: Exception) {
            HealthProgress.Failed(e.message ?: e::class.simpleName.orEmpty()).also { _progress.value = it }
        } finally {
            controller.setPolledPids(OWNER, emptyList())
        }
    }

    private suspend fun live(supported: Set<Int>): Map<String, List<Double>> = coroutineScope {
        val samples = HashMap<String, MutableList<Double>>()
        controller.setPolledPids(OWNER, LIVE_PIDS.filter { it in supported }.mapNotNull(StandardPids::byCode))
        val collector = launch {
            controller.readings.collect { r -> synchronized(samples) { samples.getOrPut(r.sensorId) { mutableListOf() } += r.value } }
        }
        var left = liveMs
        while (left > 0) {
            _progress.value = HealthProgress.Live(((left + 999) / 1000).toInt())
            val step = minOf(1_000L, left)
            delay(step)
            left -= step
        }
        collector.cancel()
        synchronized(samples) { samples.mapValues { it.value.toList() } }
    }

    private data class Snapshot(
        val stored: List<Dtc>?, val pending: List<Dtc>?, val permanent: List<Dtc>?, val can: Boolean,
        val readiness: Readiness?, val counters: ClearedCounters?, val frame: FreezeFrame?,
    )

    internal companion object {
        const val OWNER = "health"
        /** rpm, speed, coolant, trims, O2 B1S1/B1S2, module voltage; the adapter voltage comes with ATRV. */
        val LIVE_PIDS = listOf(0x0C, 0x0D, 0x05, 0x06, 0x07, 0x08, 0x09, 0x14, 0x15, 0x42)
    }
}

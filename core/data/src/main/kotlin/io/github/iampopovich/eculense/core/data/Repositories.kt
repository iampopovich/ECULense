package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.database.DtcDao
import io.github.iampopovich.eculense.core.database.DtcSnapshotEntity
import io.github.iampopovich.eculense.core.database.ReadinessSnapshotEntity
import io.github.iampopovich.eculense.core.database.ReadingEntity
import io.github.iampopovich.eculense.core.database.SessionDao
import io.github.iampopovich.eculense.core.database.SessionEntity
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.DtcKind
import io.github.iampopovich.eculense.core.model.FreezeFrame
import io.github.iampopovich.eculense.core.obd.ClearedCounters
import io.github.iampopovich.eculense.core.obd.Readiness
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.Session
import io.github.iampopovich.eculense.core.model.SessionSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Explicit timestamps and externalId are needed by the Car Scanner import (1.21); live recording does not pass them. carId = null → active car. */
interface SessionRepository {
    fun observeSessions(): Flow<List<Session>>
    suspend fun start(
        name: String,
        source: SessionSource,
        vin: String?,
        startedAtMs: Long? = null,
        externalId: String? = null,
        carId: Long? = null,
    ): Long
    suspend fun append(sessionId: Long, readings: List<SensorReading>)
    suspend fun finish(sessionId: Long, endedAtMs: Long? = null)
    suspend fun readings(sessionId: Long): List<SensorReading>
    suspend fun delete(sessionId: Long)
    suspend fun hasExternal(externalId: String): Boolean
    /** Repeat import: an already imported recording moves to the car from the backup. */
    suspend fun assignCar(externalId: String, carId: Long)
}

interface DtcRepository {
    suspend fun saveSnapshot(dtcs: List<Dtc>, vin: String?, takenAtMs: Long? = null, carId: Long? = null)

    /**
     * Successful live code read (any adapter) → history event for the active car. The same set of codes read
     * again within [DEDUP_WINDOW_MS] is not duplicated. An empty read writes nothing. true — snapshot written.
     */
    suspend fun recordRead(dtcs: List<Dtc>, vin: String? = null): Boolean = false

    /**
     * Same as [recordRead], with the freeze frame (B3) stored on the row of the code that froze it (the first
     * stored code when the ECU did not name one).
     */
    suspend fun recordRead(dtcs: List<Dtc>, vin: String?, freezeFrame: FreezeFrame?): Boolean = recordRead(dtcs, vin)

    /** B4: readiness and counters at [reason] (before/after a clear), kept in the history. */
    suspend fun saveReadiness(reason: ReadinessReason, readiness: Readiness?, counters: ClearedCounters?) {}

    /** Whether an event with this code already exists in the time window — repeat import does not duplicate events. */
    suspend fun hasEvent(code: String, fromMs: Long, toMs: Long): Boolean = false

    companion object {
        /** Pressing «Read codes» again and the snapshot before clearing do not create duplicate events. */
        const val DEDUP_WINDOW_MS = 5 * 60_000L
    }
}

internal class RoomSessionRepository(
    private val dao: SessionDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val activeCarId: suspend () -> Long? = { null },
    /** Files that belong to a recording (E5-lite accelerometer log) go with it. */
    private val onDeleted: (sessionId: Long) -> Unit = {},
) : SessionRepository {
    override fun observeSessions(): Flow<List<Session>> = dao.observeSessions().map { list -> list.map { it.toModel() } }

    override suspend fun start(name: String, source: SessionSource, vin: String?, startedAtMs: Long?, externalId: String?, carId: Long?): Long =
        dao.insertSession(
            SessionEntity(
                name = name, startedAtMs = startedAtMs ?: clock(), endedAtMs = null, source = source.name, vin = vin,
                externalId = externalId, carId = carId ?: activeCarId(),
            ),
        )

    override suspend fun append(sessionId: Long, readings: List<SensorReading>) =
        dao.insertReadings(readings.map { ReadingEntity(sessionId = sessionId, sensorId = it.sensorId, value = it.value, unit = it.unit, timestampMs = it.timestampMs) })

    override suspend fun finish(sessionId: Long, endedAtMs: Long?) = dao.finish(sessionId, endedAtMs ?: clock())

    override suspend fun readings(sessionId: Long): List<SensorReading> =
        dao.readings(sessionId).map { SensorReading(it.sensorId, it.value, it.unit, it.timestampMs) }

    /** Deleting a recording also deletes the trip derived from it; the stats follow the trips table. */
    override suspend fun delete(sessionId: Long) {
        dao.deleteWithDerivedTrips(sessionId)
        runCatching { onDeleted(sessionId) }
    }

    override suspend fun hasExternal(externalId: String): Boolean = dao.countByExternalId(externalId) > 0

    override suspend fun assignCar(externalId: String, carId: Long) = dao.assignCar(externalId, carId)

    private fun SessionEntity.toModel() = Session(id, name, startedAtMs, endedAtMs, SessionSource.valueOf(source))
}

internal class RoomDtcRepository(
    private val dao: DtcDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val activeCarId: suspend () -> Long? = { null },
    /** Live reads from a demo/replay link are not stored ([LiveWriteGate]); imports use [saveSnapshot] and are not gated. */
    private val liveWrites: LiveWriteGate = LiveWriteGate.ALWAYS,
    /** C9: every allowed live check (also one without codes) → the widget's «last check». */
    private val onCheck: suspend (count: Int, atMs: Long) -> Unit = { _, _ -> },
) : DtcRepository {
    override suspend fun saveSnapshot(dtcs: List<Dtc>, vin: String?, takenAtMs: Long?, carId: Long?) =
        save(dtcs, vin, takenAtMs, carId, null)

    private suspend fun save(dtcs: List<Dtc>, vin: String?, takenAtMs: Long?, carId: Long?, freezeFrame: FreezeFrame?) {
        val at = takenAtMs ?: clock()
        val car = carId ?: activeCarId()
        val frameCode = freezeFrame?.let { it.dtcCode ?: dtcs.firstOrNull { d -> d.kind == DtcKind.STORED }?.code }
        val encoded = freezeFrame?.let(DiagCodec::encode)
        dao.insert(
            dtcs.map {
                DtcSnapshotEntity(
                    takenAtMs = at, vin = vin, code = it.code, kind = it.kind.name, carId = car,
                    freezeFrame = encoded.takeIf { _ -> it.code == frameCode && it.kind == DtcKind.STORED },
                )
            },
        )
    }

    override suspend fun hasEvent(code: String, fromMs: Long, toMs: Long): Boolean = dao.countEvents(code, fromMs, toMs) > 0

    override suspend fun recordRead(dtcs: List<Dtc>, vin: String?): Boolean = recordRead(dtcs, vin, null)

    override suspend fun recordRead(dtcs: List<Dtc>, vin: String?, freezeFrame: FreezeFrame?): Boolean {
        if (!liveWrites.allowed()) return false
        runCatching { onCheck(dtcs.map { it.code }.distinct().size, clock()) }
        if (dtcs.isEmpty()) return false
        val at = clock()
        val car = activeCarId()
        val last = dao.latestForCar(car)
        val sameSet = last.map { it.code to it.kind }.toSet() == dtcs.map { it.code to it.kind.name }.toSet()
        val frameKnown = freezeFrame == null || last.any { it.freezeFrame != null }
        if (last.isNotEmpty() && sameSet && frameKnown && at - last.first().takenAtMs in 0..DtcRepository.DEDUP_WINDOW_MS) return false
        save(dtcs.distinct(), vin, takenAtMs = at, carId = car, freezeFrame = freezeFrame)
        return true
    }

    override suspend fun saveReadiness(reason: ReadinessReason, readiness: Readiness?, counters: ClearedCounters?) {
        if (readiness == null && counters == null) return
        if (!liveWrites.allowed()) return
        dao.insertReadiness(
            ReadinessSnapshotEntity(
                carId = activeCarId(), takenAtMs = clock(), reason = reason.name, milOn = readiness?.milOn,
                dtcCount = readiness?.dtcCount, monitors = readiness?.let(DiagCodec::encode), counters = counters?.let(DiagCodec::encode),
            ),
        )
    }
}

/** Why a readiness snapshot was stored. */
enum class ReadinessReason { BEFORE_CLEAR, AFTER_CLEAR }

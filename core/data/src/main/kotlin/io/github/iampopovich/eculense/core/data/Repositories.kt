package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.database.DtcDao
import io.github.iampopovich.eculense.core.database.DtcSnapshotEntity
import io.github.iampopovich.eculense.core.database.ReadingEntity
import io.github.iampopovich.eculense.core.database.SessionDao
import io.github.iampopovich.eculense.core.database.SessionEntity
import io.github.iampopovich.eculense.core.model.Dtc
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.model.Session
import io.github.iampopovich.eculense.core.model.SessionSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Явные метки времени и externalId нужны импорту Car Scanner (1.21); живая запись их не передаёт. carId = null → активная машина. */
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
    /** Повторный импорт: уже импортированная запись переезжает на машину из бэкапа. */
    suspend fun assignCar(externalId: String, carId: Long)
}

interface DtcRepository {
    suspend fun saveSnapshot(dtcs: List<Dtc>, vin: String?, takenAtMs: Long? = null, carId: Long? = null)

    /** Есть ли уже событие с этим кодом в окне времени — повторный импорт не дублирует события. */
    suspend fun hasEvent(code: String, fromMs: Long, toMs: Long): Boolean = false
}

internal class RoomSessionRepository(
    private val dao: SessionDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val activeCarId: suspend () -> Long? = { null },
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

    override suspend fun delete(sessionId: Long) = dao.delete(sessionId)

    override suspend fun hasExternal(externalId: String): Boolean = dao.countByExternalId(externalId) > 0

    override suspend fun assignCar(externalId: String, carId: Long) = dao.assignCar(externalId, carId)

    private fun SessionEntity.toModel() = Session(id, name, startedAtMs, endedAtMs, SessionSource.valueOf(source))
}

internal class RoomDtcRepository(
    private val dao: DtcDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val activeCarId: suspend () -> Long? = { null },
) : DtcRepository {
    override suspend fun saveSnapshot(dtcs: List<Dtc>, vin: String?, takenAtMs: Long?, carId: Long?) {
        val at = takenAtMs ?: clock()
        val car = carId ?: activeCarId()
        dao.insert(dtcs.map { DtcSnapshotEntity(takenAtMs = at, vin = vin, code = it.code, kind = it.kind.name, carId = car) })
    }

    override suspend fun hasEvent(code: String, fromMs: Long, toMs: Long): Boolean = dao.countEvents(code, fromMs, toMs) > 0
}

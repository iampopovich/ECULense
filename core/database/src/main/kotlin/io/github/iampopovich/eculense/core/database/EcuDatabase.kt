package io.github.iampopovich.eculense.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import io.github.iampopovich.eculense.core.model.LegacyValues
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** externalId: where the session came from on import ("carscanner:<file name>"); prevents duplicates on re-import. */
@Entity(tableName = "sessions", indices = [Index(value = ["externalId"], unique = true)])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val source: String,
    val vin: String?,
    val externalId: String? = null,
    /** No foreign key: ALTER TABLE can't add one, and recreating a table with 1M+ readings is risky. */
    @ColumnInfo(index = true) val carId: Long? = null,
)

/**
 * "Long" format: one row = one value of one sensor. Car Scanner CSVs are laid out the same way. The index
 * (sessionId, sensorId, timestampMs) serves every hot query: per-session aggregates (prefix sessionId), one sensor of
 * a session in time order (samples, warm start, charts) and the foreign key.
 */
@Entity(
    tableName = "readings",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId", "sensorId", "timestampMs")],
)
data class ReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val sensorId: String,
    val value: Double,
    val unit: String,
    val timestampMs: Long,
)

@Entity(tableName = "dtc_snapshots")
data class DtcSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val takenAtMs: Long,
    val vin: String?,
    val code: String,
    val kind: String,
    @ColumnInfo(index = true) val carId: Long? = null,
    /** B3: the freeze frame stored with this code (encoded by FreezeFrameCodec in :core:data); null = none. */
    val freezeFrame: String? = null,
)

/**
 * B4: readiness and the cleared-codes counters at one moment (before / after a clear), so the history keeps what a
 * clear erases. [monitors] = "MONITOR=STATUS;...", [counters] = "name=value;..." (encoded in :core:data).
 */
@Entity(tableName = "readiness_snapshots")
data class ReadinessSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(index = true) val carId: Long?,
    val takenAtMs: Long,
    val reason: String,
    val milOn: Boolean?,
    val dtcCount: Int?,
    val monitors: String?,
    val counters: String?,
)

/** Car profile. externalId is "carscanner:<car id in backup>" for re-import without duplicates. */
@Entity(tableName = "cars", indices = [Index(value = ["externalId"], unique = true)])
data class CarEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val externalId: String? = null,
    val brand: String? = null,
    val model: String? = null,
    val generation: String? = null,
    val year: Int? = null,
    val engineCode: String? = null,
    val engineVolumeL: Double? = null,
    val cylinders: Int? = null,
    val fuelType: String? = null,
    val gearbox: String? = null,
    val tankVolumeL: Double? = null,
    val odometerKm: Long? = null,
    val odometerAtMs: Long? = null,
    val vin: String? = null,
    val fuelPrice: Double? = null,
    val currency: String? = null,
    val diagProfile: String? = null,
    val oilIntervalKm: Int? = null,
    val sparkPlugsIntervalKm: Int? = null,
    val airFilterIntervalKm: Int? = null,
    val fuelFilterIntervalKm: Int? = null,
    /** v4: drivetrain (DriveType) and curb weight from the model catalog. */
    val drive: String? = null,
    val curbWeightKg: Int? = null,
)

/** Trip totals (like the Car Scanner trips table): distance in mm and fuel in µl as integers, lossless when summed. */
@Entity(
    tableName = "trips",
    foreignKeys = [ForeignKey(CarEntity::class, ["id"], ["carId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("carId"), Index(value = ["externalId"], unique = true)],
)
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val carId: Long,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val distanceMm: Long,
    val fuelUl: Long,
    val fuelPricePerL: Double?,
    val drivingMs: Long,
    val standingMs: Long,
    val source: String,
    val externalId: String? = null,
    /** v5: how the trip's fuel was computed from the recording (FuelMethod); null for a Car Scanner trip. */
    val fuelMethod: String? = null,
)

@Dao
interface SessionDao {
    @Insert suspend fun insertSession(session: SessionEntity): Long

    @Query("UPDATE sessions SET endedAtMs = :endedAtMs WHERE id = :id")
    suspend fun finish(id: Long, endedAtMs: Long)

    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Insert suspend fun insertReadings(readings: List<ReadingEntity>)

    @Query("SELECT * FROM readings WHERE sessionId = :sessionId ORDER BY timestampMs")
    suspend fun readings(sessionId: Long): List<ReadingEntity>

    /** Sensors recorded in the session (index-only scan of (sessionId, sensorId, timestampMs)). */
    @Query("SELECT DISTINCT sensorId FROM readings WHERE sessionId = :sessionId ORDER BY sensorId")
    suspend fun sensorIds(sessionId: Long): List<String>

    /** First reading time of the session (chart start, event offsets). */
    @Query("SELECT MIN(timestampMs) FROM readings WHERE sessionId = :sessionId")
    suspend fun firstTimestamp(sessionId: Long): Long?

    /** Time range and unit of one sensor in the session; null when the sensor has no readings. */
    @Query(
        "SELECT MIN(timestampMs) AS fromMs, MAX(timestampMs) AS toMs, MIN(unit) AS unit, COUNT(*) AS count " +
            "FROM readings WHERE sessionId = :sessionId AND sensorId = :sensorId GROUP BY sensorId",
    )
    suspend fun sensorRange(sessionId: Long, sensorId: String): SensorRangeRow?

    /** One page of a sensor's readings up to [toTs], (timestampMs, id) order after the keyset ([afterTs], [afterId]). */
    @Query(
        "SELECT * FROM readings WHERE sessionId = :sessionId AND sensorId = :sensorId AND timestampMs <= :toTs " +
            "AND timestampMs >= :afterTs AND (timestampMs > :afterTs OR id > :afterId) ORDER BY timestampMs, id LIMIT :limit",
    )
    suspend fun sensorPage(sessionId: Long, sensorId: String, afterTs: Long, afterId: Long, toTs: Long, limit: Int): List<ReadingEntity>

    /** One page of all readings of the session in (timestampMs, id) order after the keyset: streaming CSV export. */
    @Query(
        "SELECT * FROM readings WHERE sessionId = :sessionId AND timestampMs >= :afterTs " +
            "AND (timestampMs > :afterTs OR id > :afterId) ORDER BY timestampMs, id LIMIT :limit",
    )
    suspend fun readingsPage(sessionId: Long, afterTs: Long, afterId: Long, limit: Int): List<ReadingEntity>

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Trips computed from this recording ("session:<id>", own or imported without a Car Scanner trip). Car Scanner
     * trips that merely overlap the recording have their own externalId and are never touched.
     */
    @Query("DELETE FROM trips WHERE externalId = 'session:' || :sessionId")
    suspend fun deleteDerivedTrips(sessionId: Long): Int

    /** The recording, its readings and events (cascade) and the trip derived from it, in one transaction. */
    @androidx.room.Transaction
    suspend fun deleteWithDerivedTrips(id: Long) {
        deleteDerivedTrips(id)
        delete(id)
    }

    @Query("SELECT COUNT(*) FROM sessions WHERE externalId = :externalId")
    suspend fun countByExternalId(externalId: String): Int

    /** A recording previously imported to a different car moves to the car from the backup. */
    @Query("UPDATE sessions SET carId = :carId WHERE externalId = :externalId")
    suspend fun assignCar(externalId: String, carId: Long)
}

@Dao
interface DtcDao {
    @Insert suspend fun insert(items: List<DtcSnapshotEntity>)

    @Query("SELECT * FROM dtc_snapshots WHERE carId = :carId ORDER BY takenAtMs")
    suspend fun forCar(carId: Long): List<DtcSnapshotEntity>

    @Query("SELECT COUNT(*) FROM dtc_snapshots WHERE code = :code AND takenAtMs BETWEEN :fromMs AND :toMs")
    suspend fun countEvents(code: String, fromMs: Long, toMs: Long): Int

    /** The car's latest snapshot (all codes with the max takenAtMs); carId = null means snapshots without a car. */
    @Query("SELECT * FROM dtc_snapshots WHERE carId IS :carId AND takenAtMs = (SELECT MAX(takenAtMs) FROM dtc_snapshots WHERE carId IS :carId)")
    suspend fun latestForCar(carId: Long?): List<DtcSnapshotEntity>

    @Insert suspend fun insertReadiness(item: ReadinessSnapshotEntity): Long

    @Query("SELECT * FROM readiness_snapshots WHERE carId IS :carId ORDER BY takenAtMs DESC LIMIT :limit")
    suspend fun readinessForCar(carId: Long?, limit: Int): List<ReadinessSnapshotEntity>
}

/** Per-sensor aggregate: computed by SQLite, Kotlin gets one row instead of thousands of values. */
data class SensorAggregateRow(
    val sensorId: String,
    val unit: String,
    val count: Int,
    val min: Double,
    val max: Double,
    val mean: Double,
    val sessions: Int,
)

/** Lightweight reading projection (no id or unit) for single-pass calculations over a session. */
data class ReadingPoint(val sensorId: String, val value: Double, val timestampMs: Long)

/** Session with a value count, for lists and AI tools. */
data class SessionSummaryRow(
    val id: Long,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val source: String,
    val readings: Int,
)

/**
 * Analytics queries (stage 3). Aggregates are done in SQL without loading 1.3M values into memory; percentiles
 * use a sample thinned by position within the sensor ([Decimator], [sessionSample]/[sensorSample] in Sampling.kt).
 * "Warmed up" = after the first coolant reading ≥ `warmC` in this session; callers pass `Thresholds.WARM_COOLANT_C`
 * (:core:analytics), the one definition shared with the digest, health check and engine maps. Every readings query filters by sessionId
 * (+ sensorId) so it runs on the (sessionId, sensorId, timestampMs) index; the warm CTE is restricted to the car's
 * sessions in the period instead of scanning all coolant readings of the table.
 */
@Dao
interface AnalyticsDao {
    /** R-H: which of [ids] the car's recordings contain (index seeks per session; O2 type and MAF of imported data). */
    @Query("SELECT DISTINCT r.sensorId FROM sessions s JOIN readings r ON r.sessionId = s.id AND r.sensorId IN (:ids) WHERE s.carId = :carId")
    suspend fun carSensorsAmong(carId: Long, ids: List<String>): List<String>

    @Query(
        "SELECT s.id, s.startedAtMs, s.endedAtMs, s.source, " +
            "(SELECT COUNT(*) FROM readings r WHERE r.sessionId = s.id) AS readings " +
            "FROM sessions s WHERE s.carId = :carId AND s.startedAtMs BETWEEN :fromMs AND :toMs " +
            "ORDER BY s.startedAtMs DESC LIMIT :limit",
    )
    suspend fun sessions(carId: Long, fromMs: Long, toMs: Long, limit: Int): List<SessionSummaryRow>

    @Query("SELECT id, startedAtMs, endedAtMs FROM sessions WHERE carId = :carId ORDER BY startedAtMs")
    suspend fun sessionStarts(carId: Long): List<SessionStartRow>

    @Query("SELECT id, startedAtMs, endedAtMs, source, 0 AS readings FROM sessions WHERE id = :sessionId AND carId = :carId")
    suspend fun session(carId: Long, sessionId: Long): SessionSummaryRow?

    @Query(
        "SELECT sensorId, MIN(unit) AS unit, COUNT(*) AS count, MIN(value) AS min, MAX(value) AS max, AVG(value) AS mean, " +
            "1 AS sessions FROM readings WHERE sessionId = :sessionId GROUP BY sensorId ORDER BY sensorId",
    )
    suspend fun sessionAggregates(sessionId: Long): List<SensorAggregateRow>

    /** One page of a sensor's values in (timestampMs, id) order after the keyset ([afterTs], [afterId]). */
    @Query(
        "SELECT id, value, timestampMs FROM readings WHERE sessionId = :sessionId AND sensorId = :sensorId " +
            "AND timestampMs >= :afterTs AND (timestampMs > :afterTs OR id > :afterId) ORDER BY timestampMs, id LIMIT :limit",
    )
    suspend fun valuesPage(sessionId: Long, sensorId: String, afterTs: Long, afterId: Long, limit: Int): List<ValueRow>

    /** Time of the first coolant reading >= [warmC] °C in the session; null = never warmed up. */
    @Query("SELECT MIN(timestampMs) FROM readings WHERE sessionId = :sessionId AND sensorId = '01.05' AND value >= :warmC")
    suspend fun warmStart(sessionId: Long, warmC: Double): Long?

    @Query(
        "SELECT r.sensorId, MIN(r.unit) AS unit, COUNT(*) AS count, MIN(r.value) AS min, MAX(r.value) AS max, " +
            "AVG(r.value) AS mean, COUNT(DISTINCT r.sessionId) AS sessions " +
            "FROM readings r JOIN sessions s ON s.id = r.sessionId " +
            "WHERE s.carId = :carId AND s.startedAtMs BETWEEN :fromMs AND :toMs AND r.sensorId = :sensorId " +
            "GROUP BY r.sensorId",
    )
    suspend fun sensorAggregate(carId: Long, sensorId: String, fromMs: Long, toMs: Long): SensorAggregateRow?

    @Query(
        "WITH warm AS (SELECT r.sessionId, MIN(r.timestampMs) AS t FROM sessions s JOIN readings r ON r.sessionId = s.id " +
            "WHERE s.carId = :carId AND s.startedAtMs BETWEEN :fromMs AND :toMs AND r.sensorId = '01.05' AND r.value >= :warmC " +
            "GROUP BY r.sessionId) " +
            "SELECT r.sensorId, MIN(r.unit) AS unit, COUNT(*) AS count, MIN(r.value) AS min, MAX(r.value) AS max, " +
            "AVG(r.value) AS mean, COUNT(DISTINCT r.sessionId) AS sessions " +
            "FROM readings r JOIN sessions s ON s.id = r.sessionId JOIN warm w ON w.sessionId = r.sessionId " +
            "WHERE s.carId = :carId AND s.startedAtMs BETWEEN :fromMs AND :toMs AND r.sensorId = :sensorId AND r.timestampMs >= w.t " +
            "GROUP BY r.sensorId",
    )
    suspend fun warmSensorAggregate(carId: Long, sensorId: String, fromMs: Long, toMs: Long, warmC: Double): SensorAggregateRow?

    /** Only the sensors the rules need for one session, ordered by time: input for the single-pass digest. */
    @Query("SELECT sensorId, value, timestampMs FROM readings WHERE sessionId = :sessionId AND sensorId IN (:sensorIds) ORDER BY timestampMs")
    suspend fun points(sessionId: Long, sensorIds: Collection<String>): List<ReadingPoint>
}

data class ValueRow(val id: Long, val value: Double, val timestampMs: Long)

data class SensorRangeRow(val fromMs: Long, val toMs: Long, val unit: String, val count: Int)

data class SessionStartRow(val id: Long, val startedAtMs: Long, val endedAtMs: Long? = null)

@Dao
interface CarDao {
    @Query("SELECT * FROM cars ORDER BY id")
    fun observeAll(): Flow<List<CarEntity>>

    @Query("SELECT * FROM cars ORDER BY id")
    suspend fun all(): List<CarEntity>

    @Query("SELECT * FROM cars WHERE id = :id")
    suspend fun byId(id: Long): CarEntity?

    @Query("SELECT * FROM cars WHERE externalId = :externalId")
    suspend fun byExternalId(externalId: String): CarEntity?

    @Insert suspend fun insert(car: CarEntity): Long

    @Update suspend fun update(car: CarEntity)

    @Query("DELETE FROM cars WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE sessions SET carId = NULL WHERE carId = :id")
    suspend fun detachSessions(id: Long)

    @Query("UPDATE dtc_snapshots SET carId = NULL WHERE carId = :id")
    suspend fun detachDtc(id: Long)
}

@Dao
interface TripDao {
    @Query("SELECT * FROM trips WHERE carId = :carId ORDER BY startedAtMs DESC")
    fun observeForCar(carId: Long): Flow<List<TripEntity>>

    /** IGNORE + unique externalId = re-import doesn't duplicate trips; -1 means it already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(trips: List<TripEntity>): List<Long>

    @Query("SELECT * FROM trips WHERE carId = :carId ORDER BY startedAtMs")
    suspend fun forCar(carId: Long): List<TripEntity>

    @Query("SELECT * FROM trips WHERE externalId IN (:externalIds)")
    suspend fun byExternalIds(externalIds: List<String>): List<TripEntity>

    /** A Car Scanner trip extended/changed in a new backup (same externalId). */
    @Update suspend fun updateAll(trips: List<TripEntity>)

    /**
     * Finished recordings without their own trip ("session:<id>"). Imported recordings that overlap in time
     * with a Car Scanner trip are skipped: that trip is already in the table, no duplicate needed.
     */
    @Query(
        "SELECT s.id, s.startedAtMs, s.endedAtMs, s.source, s.carId FROM sessions s " +
            "WHERE s.endedAtMs IS NOT NULL AND s.carId IS NOT NULL " +
            "AND NOT EXISTS (SELECT 1 FROM trips t WHERE t.externalId = 'session:' || s.id) " +
            "AND (s.source = 'LIVE' OR NOT EXISTS (SELECT 1 FROM trips t WHERE t.carId = s.carId " +
            "AND t.source = 'IMPORT_CARSCANNER' AND t.startedAtMs < s.endedAtMs AND t.endedAtMs > s.startedAtMs)) " +
            "ORDER BY s.startedAtMs",
    )
    suspend fun sessionsWithoutTrip(): List<TripCandidateRow>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun byId(id: Long): TripEntity?

    /** D4: the recording behind a trip that has no «session:<id>» link (a Car Scanner trip): the first overlapping one. */
    @Query(
        "SELECT id FROM sessions WHERE carId = :carId AND startedAtMs <= :endMs AND (endedAtMs IS NULL OR endedAtMs >= :startMs) " +
            "ORDER BY startedAtMs LIMIT 1",
    )
    suspend fun sessionOverlapping(carId: Long, startMs: Long, endMs: Long): Long?
}

data class TripCandidateRow(val id: Long, val startedAtMs: Long, val endedAtMs: Long, val source: String, val carId: Long)

/** A car maintenance task (3.3/3.9 item 2): mileage/time intervals and when it was last done. */
@Entity(
    tableName = "maintenance_items",
    foreignKeys = [ForeignKey(CarEntity::class, ["id"], ["carId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("carId")],
)
data class MaintenanceItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val carId: Long,
    val title: String,
    val intervalKm: Int?,
    val intervalMonths: Int?,
    val lastDoneKm: Long?,
    val lastDoneAtMs: Long?,
    val note: String?,
    /** MaintenanceKind name of a template work (title is blank then), null for the user's own works. */
    val kind: String? = null,
    /** MaintenanceNote name of a template note (note is null then). */
    val noteKind: String? = null,
)

/** Odometer entries: MANUAL is user input (or migrated from the car profile), ESTIMATED is an estimate. */
@Entity(
    tableName = "odometer_log",
    foreignKeys = [ForeignKey(CarEntity::class, ["id"], ["carId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("carId")],
)
data class OdometerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val carId: Long,
    val km: Long,
    val atMs: Long,
    val source: String,
)

@Dao
interface MaintenanceDao {
    @Query("SELECT * FROM maintenance_items WHERE carId = :carId ORDER BY id")
    fun observeForCar(carId: Long): Flow<List<MaintenanceItemEntity>>

    @Query("SELECT * FROM maintenance_items WHERE carId = :carId ORDER BY id")
    suspend fun forCar(carId: Long): List<MaintenanceItemEntity>

    @Query("SELECT * FROM maintenance_items WHERE id = :id")
    suspend fun byId(id: Long): MaintenanceItemEntity?

    @Insert suspend fun insert(item: MaintenanceItemEntity): Long

    @Insert suspend fun insertAll(items: List<MaintenanceItemEntity>)

    @Update suspend fun update(item: MaintenanceItemEntity)

    @Query("DELETE FROM maintenance_items WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface OdometerDao {
    @Query("SELECT * FROM odometer_log WHERE carId = :carId ORDER BY atMs, id")
    fun observeForCar(carId: Long): Flow<List<OdometerEntity>>

    @Query("SELECT * FROM odometer_log WHERE carId = :carId ORDER BY atMs, id")
    suspend fun forCar(carId: Long): List<OdometerEntity>

    /** The latest manual entry for each car (for the car profile). */
    @Query(
        "SELECT * FROM odometer_log o WHERE o.source = 'MANUAL' AND o.id = (SELECT o2.id FROM odometer_log o2 " +
            "WHERE o2.carId = o.carId AND o2.source = 'MANUAL' ORDER BY o2.atMs DESC, o2.id DESC LIMIT 1)",
    )
    fun observeLatestManual(): Flow<List<OdometerEntity>>

    @Query(
        "SELECT * FROM odometer_log WHERE carId = :carId AND source = 'MANUAL' ORDER BY atMs DESC, id DESC LIMIT 1",
    )
    suspend fun latestManual(carId: Long): OdometerEntity?

    @Insert suspend fun insert(entry: OdometerEntity): Long

    @Query("DELETE FROM odometer_log WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * v6 (1.27): an import history row: what one backup brought in. status: DONE / FAILED / CANCELLED.
 * fileName is the name of the selected file (local only, never logged).
 */
@Entity(tableName = "import_history")
data class ImportHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val fileName: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val status: String,
    val recordingsAdded: Int,
    val recordingsSkipped: Int,
    val tripsAdded: Int,
    val tripsUpdated: Int,
    val tripsSkipped: Int,
    val dtcAdded: Int,
    val carFieldsUpdated: Int,
    val unitsUpdated: Boolean,
    val errors: Int,
    val message: String?,
)

/** v6: a .brc entry imported in full (name + size + CRC from the zip); a repeat backup skips it without unpacking. */
@Entity(tableName = "import_files")
data class ImportedFileEntity(
    @PrimaryKey val name: String,
    val size: Long,
    val crc: Long,
    val importedAtMs: Long,
)

/**
 * v6: values brought in by the previous import (JSON), keyed by "car:<id>" / "units". A field is updated from a new
 * backup only if the user hasn't changed it since the previous import (current value == snapshot).
 */
@Entity(tableName = "import_snapshots")
data class ImportSnapshotEntity(
    @PrimaryKey val key: String,
    val json: String,
)

@Dao
interface ImportDao {
    @Insert suspend fun insertHistory(row: ImportHistoryEntity): Long

    @Query("SELECT * FROM import_history ORDER BY startedAtMs DESC, id DESC LIMIT :limit")
    fun observeHistory(limit: Int = 50): Flow<List<ImportHistoryEntity>>

    @Query("SELECT * FROM import_files WHERE name = :name")
    suspend fun file(name: String): ImportedFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFile(file: ImportedFileEntity)

    @Query("SELECT json FROM import_snapshots WHERE `key` = :key")
    suspend fun snapshot(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSnapshot(snapshot: ImportSnapshotEntity)
}

@Database(
    entities = [
        SessionEntity::class, ReadingEntity::class, DtcSnapshotEntity::class, CarEntity::class, TripEntity::class, FindingEntity::class,
        MaintenanceItemEntity::class, OdometerEntity::class, ImportHistoryEntity::class, ImportedFileEntity::class, ImportSnapshotEntity::class,
        ReadinessSnapshotEntity::class, TripEventEntity::class, HealthCheckEntity::class, FuelFillUpEntity::class, ExpenseEntity::class,
        CarNormEntity::class, ModuleScanEntity::class,
    ],
    version = EcuDatabase.VERSION,
)
abstract class EcuDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun dtcDao(): DtcDao
    abstract fun carDao(): CarDao
    abstract fun tripDao(): TripDao
    abstract fun analyticsDao(): AnalyticsDao
    abstract fun findingDao(): FindingDao
    abstract fun maintenanceDao(): MaintenanceDao
    abstract fun odometerDao(): OdometerDao
    abstract fun importDao(): ImportDao
    abstract fun eventDao(): EventDao
    abstract fun healthDao(): HealthDao
    abstract fun sessionMoveDao(): SessionMoveDao
    abstract fun fuelDao(): FuelDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun odometerSyncDao(): OdometerSyncDao
    abstract fun carNormDao(): CarNormDao
    abstract fun moduleScanDao(): ModuleScanDao

    companion object {
        /** Schema version; an ECULense backup with a newer version is not restored. */
        const val VERSION = 13
        const val FILE_NAME = "ecu.db"
        val MIGRATIONS: Array<Migration> get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)

        const val DEFAULT_CAR_ID = 1L
        /** Blank: the UI shows the localized default name (older DBs keep their Russian literal, see LegacyValues). */
        const val DEFAULT_CAR_NAME = ""

        /** The first car always exists: new recordings and legacy data are attached to it. */
        val defaultCar = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("INSERT INTO cars (id, name) VALUES ($DEFAULT_CAR_ID, '$DEFAULT_CAR_NAME')")
            }
        }
    }
}

/**
 * 1 → 2: cars and trips (task 1.25). Only CREATE and ALTER TABLE ADD COLUMN: the sessions/readings tables
 * are not recreated, so the readings → sessions cascade doesn't fire and user recordings are left untouched.
 * All existing sessions and DTC snapshots are attached to the default car.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `cars` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`externalId` TEXT, `brand` TEXT, `model` TEXT, `generation` TEXT, `year` INTEGER, `engineCode` TEXT, " +
                "`engineVolumeL` REAL, `cylinders` INTEGER, `fuelType` TEXT, `gearbox` TEXT, `tankVolumeL` REAL, " +
                "`odometerKm` INTEGER, `odometerAtMs` INTEGER, `vin` TEXT, `fuelPrice` REAL, `currency` TEXT, `diagProfile` TEXT, " +
                "`oilIntervalKm` INTEGER, `sparkPlugsIntervalKm` INTEGER, `airFilterIntervalKm` INTEGER, `fuelFilterIntervalKm` INTEGER)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_cars_externalId` ON `cars` (`externalId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `trips` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `carId` INTEGER NOT NULL, " +
                "`startedAtMs` INTEGER NOT NULL, `endedAtMs` INTEGER NOT NULL, `distanceMm` INTEGER NOT NULL, `fuelUl` INTEGER NOT NULL, " +
                "`fuelPricePerL` REAL, `drivingMs` INTEGER NOT NULL, `standingMs` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
                "`externalId` TEXT, FOREIGN KEY(`carId`) REFERENCES `cars`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trips_carId` ON `trips` (`carId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_trips_externalId` ON `trips` (`externalId`)")
        db.execSQL("ALTER TABLE `sessions` ADD COLUMN `carId` INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_carId` ON `sessions` (`carId`)")
        db.execSQL("ALTER TABLE `dtc_snapshots` ADD COLUMN `carId` INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_dtc_snapshots_carId` ON `dtc_snapshots` (`carId`)")
        db.execSQL("INSERT INTO cars (id, name) VALUES (${EcuDatabase.DEFAULT_CAR_ID}, '${EcuDatabase.DEFAULT_CAR_NAME}')")
        db.execSQL("UPDATE sessions SET carId = ${EcuDatabase.DEFAULT_CAR_ID}")
        db.execSQL("UPDATE dtc_snapshots SET carId = ${EcuDatabase.DEFAULT_CAR_ID}")
    }
}

/**
 * An analytics finding (stage 3) for a car. evidenceJson is {"evidence":[{"label","value"}],"sessions":[id…]};
 * recomputation replaces all of the car's findings at once ([FindingDao.replaceForCar]).
 */
@Entity(
    tableName = "findings",
    foreignKeys = [ForeignKey(CarEntity::class, ["id"], ["carId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("carId")],
)
data class FindingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val carId: Long,
    val ruleId: String,
    val severity: String,
    val title: String,
    val message: String,
    val evidenceJson: String,
    val periodFromMs: Long?,
    val periodToMs: Long?,
    val createdAtMs: Long,
)

@Dao
interface FindingDao {
    @Query("SELECT * FROM findings WHERE carId = :carId ORDER BY id")
    fun observeForCar(carId: Long): Flow<List<FindingEntity>>

    @Query("SELECT * FROM findings WHERE carId = :carId ORDER BY id")
    suspend fun forCar(carId: Long): List<FindingEntity>

    @Query("SELECT * FROM findings WHERE id = :id")
    suspend fun byId(id: Long): FindingEntity?

    @Query("DELETE FROM findings WHERE carId = :carId")
    suspend fun deleteForCar(carId: Long)

    @Insert suspend fun insertAll(items: List<FindingEntity>)

    @androidx.room.Transaction
    suspend fun replaceForCar(carId: Long, items: List<FindingEntity>) {
        deleteForCar(carId)
        insertAll(items)
    }
}

/** 2 → 3: analytics findings table. Only CREATE: existing tables and data are untouched. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `findings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `carId` INTEGER NOT NULL, " +
                "`ruleId` TEXT NOT NULL, `severity` TEXT NOT NULL, `title` TEXT NOT NULL, `message` TEXT NOT NULL, " +
                "`evidenceJson` TEXT NOT NULL, `periodFromMs` INTEGER, `periodToMs` INTEGER, `createdAtMs` INTEGER NOT NULL, " +
                "FOREIGN KEY(`carId`) REFERENCES `cars`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_findings_carId` ON `findings` (`carId`)")
    }
}

/** 3 → 4: car drivetrain and weight (model catalog, 1.25b). Only ADD COLUMN: data is untouched. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `cars` ADD COLUMN `drive` TEXT")
        db.execSQL("ALTER TABLE `cars` ADD COLUMN `curbWeightKg` INTEGER")
    }
}

/**
 * 4 → 5: maintenance and odometer log (3.9 item 2), fuel calculation method for trips. Only CREATE and ADD COLUMN.
 * Mileage from the car profile (1.25: odometerKm/odometerAtMs) is moved to odometer_log as manual input and
 * cleared in cars: a single source, the profile reads mileage from the log. No date means the migration date.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `trips` ADD COLUMN `fuelMethod` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `maintenance_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `carId` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, `intervalKm` INTEGER, `intervalMonths` INTEGER, `lastDoneKm` INTEGER, `lastDoneAtMs` INTEGER, " +
                "`note` TEXT, FOREIGN KEY(`carId`) REFERENCES `cars`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_maintenance_items_carId` ON `maintenance_items` (`carId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `odometer_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `carId` INTEGER NOT NULL, " +
                "`km` INTEGER NOT NULL, `atMs` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
                "FOREIGN KEY(`carId`) REFERENCES `cars`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_odometer_log_carId` ON `odometer_log` (`carId`)")
        db.execSQL(
            "INSERT INTO odometer_log (carId, km, atMs, source) SELECT id, odometerKm, " +
                "COALESCE(odometerAtMs, CAST(strftime('%s','now') AS INTEGER) * 1000), 'MANUAL' FROM cars WHERE odometerKm IS NOT NULL",
        )
        db.execSQL("UPDATE cars SET odometerKm = NULL, odometerAtMs = NULL")
    }
}

/** 5 → 6: import history, markers of imported .brc files and previous-import snapshots (1.27). Only CREATE. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `import_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source` TEXT NOT NULL, " +
                "`fileName` TEXT NOT NULL, `startedAtMs` INTEGER NOT NULL, `finishedAtMs` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                "`recordingsAdded` INTEGER NOT NULL, `recordingsSkipped` INTEGER NOT NULL, `tripsAdded` INTEGER NOT NULL, " +
                "`tripsUpdated` INTEGER NOT NULL, `tripsSkipped` INTEGER NOT NULL, `dtcAdded` INTEGER NOT NULL, " +
                "`carFieldsUpdated` INTEGER NOT NULL, `unitsUpdated` INTEGER NOT NULL, `errors` INTEGER NOT NULL, `message` TEXT)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `import_files` (`name` TEXT NOT NULL, `size` INTEGER NOT NULL, `crc` INTEGER NOT NULL, " +
                "`importedAtMs` INTEGER NOT NULL, PRIMARY KEY(`name`))",
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS `import_snapshots` (`key` TEXT NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`key`))")
    }
}

/**
 * 6 → 7 (localization): maintenance works get a typed kind/note. Template works written in Russian by older versions are
 * recognized by their exact title/note ([LegacyValues]) and switched to kind + blank title, so the UI shows them in the
 * app language. Works the user created or renamed keep their text. Only ADD COLUMN and UPDATE: nothing is deleted.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `maintenance_items` ADD COLUMN `kind` TEXT")
        db.execSQL("ALTER TABLE `maintenance_items` ADD COLUMN `noteKind` TEXT")
        LegacyValues.MAINTENANCE_TITLES_RU.forEach { (title, kind) ->
            db.execSQL("UPDATE maintenance_items SET kind = ?, title = '' WHERE kind IS NULL AND title = ?", arrayOf<Any>(kind.name, title))
        }
        LegacyValues.MAINTENANCE_NOTES_RU.forEach { (note, kind) ->
            db.execSQL("UPDATE maintenance_items SET noteKind = ?, note = NULL WHERE noteKind IS NULL AND note = ?", arrayOf<Any>(kind.name, note))
        }
    }
}

/**
 * 7 → 8 (diagnostics, B3/B4): the freeze frame column on DTC snapshots and the readiness snapshots table. Only ADD
 * COLUMN and CREATE TABLE: existing rows are untouched.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `dtc_snapshots` ADD COLUMN `freezeFrame` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `readiness_snapshots` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `carId` INTEGER, " +
                "`takenAtMs` INTEGER NOT NULL, `reason` TEXT NOT NULL, `milOn` INTEGER, `dtcCount` INTEGER, `monitors` TEXT, `counters` TEXT)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_readiness_snapshots_carId` ON `readiness_snapshots` (`carId`)")
    }
}

/**
 * 10 → 11 (R2.1): readings get the (sessionId, sensorId, timestampMs) index instead of the single sessionId one. Only
 * CREATE/DROP INDEX: no row is touched. The build time is logged (tag EcuDb): about a second per million readings.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val started = System.nanoTime()
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_readings_sessionId_sensorId_timestampMs` " +
                "ON `readings` (`sessionId`, `sensorId`, `timestampMs`)",
        )
        db.execSQL("DROP INDEX IF EXISTS `index_readings_sessionId`")
        val ms = (System.nanoTime() - started) / 1_000_000
        runCatching { android.util.Log.i("EcuDb", "migration 10->11: readings index built in $ms ms") }
    }
}

val databaseModule = module {
    single {
        Room.databaseBuilder(androidContext(), EcuDatabase::class.java, EcuDatabase.FILE_NAME)
            .addMigrations(*EcuDatabase.MIGRATIONS)
            .addCallback(EcuDatabase.defaultCar)
            .build()
    }
    single { get<EcuDatabase>().sessionDao() }
    single { get<EcuDatabase>().dtcDao() }
    single { get<EcuDatabase>().carDao() }
    single { get<EcuDatabase>().tripDao() }
    single { get<EcuDatabase>().analyticsDao() }
    single { get<EcuDatabase>().findingDao() }
    single { get<EcuDatabase>().maintenanceDao() }
    single { get<EcuDatabase>().odometerDao() }
    single { get<EcuDatabase>().importDao() }
    single { get<EcuDatabase>().eventDao() }
    single { get<EcuDatabase>().healthDao() }
    single { get<EcuDatabase>().sessionMoveDao() }
    single { get<EcuDatabase>().fuelDao() }
    single { get<EcuDatabase>().expenseDao() }
    single { get<EcuDatabase>().odometerSyncDao() }
    single { get<EcuDatabase>().carNormDao() }
    single { get<EcuDatabase>().moduleScanDao() }
}

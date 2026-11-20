package io.github.iampopovich.eculense.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * v9 (B10): an event inside a recording: an engine stall found by the app or a mark the user tapped while driving.
 * Deleting the recording deletes its events. [type] = TripEventType name.
 */
@Entity(
    tableName = "trip_events",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index("carId")],
)
data class TripEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val carId: Long?,
    val atMs: Long,
    val type: String,
)

/**
 * v9 (B6): one «quick health check». [verdict] = OK / WARNING / FAULT; [items] = encoded check results, [dtcs] =
 * encoded codes with freeze frames, [readiness] = encoded monitors (codecs in :core:data). No VIN, no location.
 */
@Entity(tableName = "health_checks", indices = [Index("carId")])
data class HealthCheckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val carId: Long?,
    val takenAtMs: Long,
    val verdict: String,
    val items: String,
    val dtcs: String?,
    val readiness: String?,
    val counters: String?,
    val odometerKm: Long?,
)

@Dao
interface EventDao {
    @Insert suspend fun insert(event: TripEventEntity): Long

    @Query("SELECT * FROM trip_events WHERE sessionId = :sessionId ORDER BY atMs")
    suspend fun forSession(sessionId: Long): List<TripEventEntity>

    @Query("SELECT * FROM trip_events WHERE sessionId = :sessionId ORDER BY atMs")
    fun observeForSession(sessionId: Long): Flow<List<TripEventEntity>>

    @Query("SELECT * FROM trip_events WHERE carId = :carId AND atMs BETWEEN :fromMs AND :toMs ORDER BY atMs")
    suspend fun forCar(carId: Long, fromMs: Long, toMs: Long): List<TripEventEntity>
}

@Dao
interface HealthDao {
    @Insert suspend fun insert(check: HealthCheckEntity): Long

    @Query("SELECT * FROM health_checks WHERE carId IS :carId ORDER BY takenAtMs DESC LIMIT :limit")
    fun observeForCar(carId: Long?, limit: Int = 50): Flow<List<HealthCheckEntity>>

    @Query("SELECT * FROM health_checks WHERE id = :id")
    suspend fun byId(id: Long): HealthCheckEntity?
}

/** C8: a recording moves to another car together with its derived trip and its events. */
@Dao
interface SessionMoveDao {
    @Query("SELECT carId FROM sessions WHERE id = :sessionId")
    suspend fun carOf(sessionId: Long): Long?

    @Query("UPDATE sessions SET carId = :carId WHERE id = :sessionId")
    suspend fun moveSession(sessionId: Long, carId: Long)

    @Query("UPDATE trips SET carId = :carId WHERE externalId = 'session:' || :sessionId")
    suspend fun moveTrips(sessionId: Long, carId: Long): Int

    @Query("UPDATE trip_events SET carId = :carId WHERE sessionId = :sessionId")
    suspend fun moveEvents(sessionId: Long, carId: Long)

    @Transaction
    suspend fun move(sessionId: Long, carId: Long) {
        moveSession(sessionId, carId)
        moveTrips(sessionId, carId)
        moveEvents(sessionId, carId)
    }
}

/** 8 → 9 (B10, B6): trip events and health checks. Only CREATE TABLE: existing rows are untouched. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `trip_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, " +
                "`carId` INTEGER, `atMs` INTEGER NOT NULL, `type` TEXT NOT NULL, " +
                "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_events_sessionId` ON `trip_events` (`sessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_events_carId` ON `trip_events` (`carId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `health_checks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `carId` INTEGER, " +
                "`takenAtMs` INTEGER NOT NULL, `verdict` TEXT NOT NULL, `items` TEXT NOT NULL, `dtcs` TEXT, `readiness` TEXT, " +
                "`counters` TEXT, `odometerKm` INTEGER)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_health_checks_carId` ON `health_checks` (`carId`)")
    }
}

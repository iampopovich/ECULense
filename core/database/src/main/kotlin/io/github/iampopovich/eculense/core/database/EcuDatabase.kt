package io.github.iampopovich.eculense.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** externalId — откуда пришла сессия при импорте ("carscanner:<имя файла>"), защищает от дублей при повторном импорте. */
@Entity(tableName = "sessions", indices = [Index(value = ["externalId"], unique = true)])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val source: String,
    val vin: String?,
    val externalId: String? = null,
)

/** «Длинный» формат: одна строка = одно значение одного датчика. Так же устроены CSV Car Scanner. */
@Entity(
    tableName = "readings",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
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

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM sessions WHERE externalId = :externalId")
    suspend fun countByExternalId(externalId: String): Int
}

@Dao
interface DtcDao {
    @Insert suspend fun insert(items: List<DtcSnapshotEntity>)
}

@Database(entities = [SessionEntity::class, ReadingEntity::class, DtcSnapshotEntity::class], version = 1)
abstract class EcuDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun dtcDao(): DtcDao
}

val databaseModule = module {
    single { Room.databaseBuilder(androidContext(), EcuDatabase::class.java, "ecu.db").build() }
    single { get<EcuDatabase>().sessionDao() }
    single { get<EcuDatabase>().dtcDao() }
}

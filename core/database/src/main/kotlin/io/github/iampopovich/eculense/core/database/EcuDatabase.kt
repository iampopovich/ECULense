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
import androidx.sqlite.db.SupportSQLiteDatabase
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
    /** Без внешнего ключа: ALTER TABLE не умеет его добавить, а пересоздание таблицы с 1+ млн показаний рискованно. */
    @ColumnInfo(index = true) val carId: Long? = null,
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
    @ColumnInfo(index = true) val carId: Long? = null,
)

/** Профиль машины. externalId — "carscanner:<id машины в бэкапе>" для повторного импорта без дублей. */
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
    /** v4: привод (DriveType) и снаряжённая масса из справочника моделей. */
    val drive: String? = null,
    val curbWeightKg: Int? = null,
)

/** Итоги поездки (как таблица trips Car Scanner): путь в мм и топливо в мкл — целые, без потерь при суммировании. */
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

    /** Запись, импортированная раньше на другую машину, переезжает на машину из бэкапа. */
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
}

/** Агрегат одного датчика: считает SQLite, в Kotlin приходит одна строка вместо тысяч значений. */
data class SensorAggregateRow(
    val sensorId: String,
    val unit: String,
    val count: Int,
    val min: Double,
    val max: Double,
    val mean: Double,
    val sessions: Int,
)

/** Лёгкая проекция показания (без id и единицы) для однопроходных расчётов по сессии. */
data class ReadingPoint(val sensorId: String, val value: Double, val timestampMs: Long)

/** Сессия со счётчиком значений — для списков и инструментов ИИ. */
data class SessionSummaryRow(
    val id: Long,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val source: String,
    val readings: Int,
)

/**
 * Запросы аналитики (этап 3). Агрегаты — в SQL, без загрузки 1,3 млн значений в память; перцентили —
 * по прореженной выборке (`id % step = 0`). «Прогретый» = после первого замера ОЖ ≥ 80 °C в этой сессии.
 * Индекс по sessionId уже есть; полный проход по readings на телефоне — 0,2–2,5 с, отдельный индекс не нужен.
 */
@Dao
interface AnalyticsDao {
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

    @Query("SELECT value FROM readings WHERE sessionId = :sessionId AND sensorId = :sensorId AND id % :step = 0")
    suspend fun sessionSample(sessionId: Long, sensorId: String, step: Int): List<Double>

    @Query(
        "SELECT r.sensorId, MIN(r.unit) AS unit, COUNT(*) AS count, MIN(r.value) AS min, MAX(r.value) AS max, " +
            "AVG(r.value) AS mean, COUNT(DISTINCT r.sessionId) AS sessions " +
            "FROM readings r JOIN sessions s ON s.id = r.sessionId " +
            "WHERE s.carId = :carId AND s.startedAtMs BETWEEN :fromMs AND :toMs AND r.sensorId = :sensorId " +
            "GROUP BY r.sensorId",
    )
    suspend fun sensorAggregate(carId: Long, sensorId: String, fromMs: Long, toMs: Long): SensorAggregateRow?

    @Query(
        "WITH warm AS (SELECT sessionId, MIN(timestampMs) AS t FROM readings WHERE sensorId = '01.05' AND value >= 80 GROUP BY sessionId) " +
            "SELECT r.sensorId, MIN(r.unit) AS unit, COUNT(*) AS count, MIN(r.value) AS min, MAX(r.value) AS max, " +
            "AVG(r.value) AS mean, COUNT(DISTINCT r.sessionId) AS sessions " +
            "FROM readings r JOIN sessions s ON s.id = r.sessionId JOIN warm w ON w.sessionId = r.sessionId " +
            "WHERE s.carId = :carId AND s.startedAtMs BETWEEN :fromMs AND :toMs AND r.sensorId = :sensorId AND r.timestampMs >= w.t " +
            "GROUP BY r.sensorId",
    )
    suspend fun warmSensorAggregate(carId: Long, sensorId: String, fromMs: Long, toMs: Long): SensorAggregateRow?

    @Query(
        "SELECT r.value FROM readings r JOIN sessions s ON s.id = r.sessionId " +
            "WHERE s.carId = :carId AND s.startedAtMs BETWEEN :fromMs AND :toMs AND r.sensorId = :sensorId AND r.id % :step = 0",
    )
    suspend fun sensorSample(carId: Long, sensorId: String, fromMs: Long, toMs: Long, step: Int): List<Double>

    @Query(
        "WITH warm AS (SELECT sessionId, MIN(timestampMs) AS t FROM readings WHERE sensorId = '01.05' AND value >= 80 GROUP BY sessionId) " +
            "SELECT r.value FROM readings r JOIN sessions s ON s.id = r.sessionId JOIN warm w ON w.sessionId = r.sessionId " +
            "WHERE s.carId = :carId AND s.startedAtMs BETWEEN :fromMs AND :toMs AND r.sensorId = :sensorId " +
            "AND r.timestampMs >= w.t AND r.id % :step = 0",
    )
    suspend fun warmSensorSample(carId: Long, sensorId: String, fromMs: Long, toMs: Long, step: Int): List<Double>

    /** Только нужные правилам датчики одной сессии, по времени — вход для однопроходного дайджеста. */
    @Query("SELECT sensorId, value, timestampMs FROM readings WHERE sessionId = :sessionId AND sensorId IN (:sensorIds) ORDER BY timestampMs")
    suspend fun points(sessionId: Long, sensorIds: Collection<String>): List<ReadingPoint>
}

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

    /** IGNORE + уникальный externalId = повторный импорт не дублирует поездки; -1 — такая уже была. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(trips: List<TripEntity>): List<Long>
}

@Database(
    entities = [SessionEntity::class, ReadingEntity::class, DtcSnapshotEntity::class, CarEntity::class, TripEntity::class, FindingEntity::class],
    version = 4,
)
abstract class EcuDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun dtcDao(): DtcDao
    abstract fun carDao(): CarDao
    abstract fun tripDao(): TripDao
    abstract fun analyticsDao(): AnalyticsDao
    abstract fun findingDao(): FindingDao

    companion object {
        const val DEFAULT_CAR_ID = 1L
        const val DEFAULT_CAR_NAME = "Мой автомобиль"

        /** Первая машина есть всегда: новые записи и старые данные привязываются к ней. */
        val defaultCar = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("INSERT INTO cars (id, name) VALUES ($DEFAULT_CAR_ID, '$DEFAULT_CAR_NAME')")
            }
        }
    }
}

/**
 * 1 → 2: машины и поездки (задача 1.25). Только CREATE и ALTER TABLE ADD COLUMN — таблицы sessions/readings
 * не пересоздаются, поэтому каскад readings → sessions не срабатывает и записи пользователя не трогаются.
 * Все существующие сессии и снимки DTC привязываются к машине по умолчанию.
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
 * Находка аналитики (этап 3) для машины. evidenceJson — {"evidence":[{"label","value"}],"sessions":[id…]};
 * пересчёт заменяет все находки машины разом ([FindingDao.replaceForCar]).
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

/** 2 → 3: таблица находок аналитики. Только CREATE — существующие таблицы и данные не трогаются. */
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

/** 3 → 4: привод и масса машины (справочник моделей, 1.25b). Только ADD COLUMN — данные не трогаются. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `cars` ADD COLUMN `drive` TEXT")
        db.execSQL("ALTER TABLE `cars` ADD COLUMN `curbWeightKg` INTEGER")
    }
}

val databaseModule = module {
    single {
        Room.databaseBuilder(androidContext(), EcuDatabase::class.java, "ecu.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .addCallback(EcuDatabase.defaultCar)
            .build()
    }
    single { get<EcuDatabase>().sessionDao() }
    single { get<EcuDatabase>().dtcDao() }
    single { get<EcuDatabase>().carDao() }
    single { get<EcuDatabase>().tripDao() }
    single { get<EcuDatabase>().analyticsDao() }
    single { get<EcuDatabase>().findingDao() }
}

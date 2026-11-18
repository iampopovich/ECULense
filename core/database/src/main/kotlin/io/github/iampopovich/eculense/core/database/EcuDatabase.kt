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
}

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
    entities = [SessionEntity::class, ReadingEntity::class, DtcSnapshotEntity::class, CarEntity::class, TripEntity::class],
    version = 2,
)
abstract class EcuDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun dtcDao(): DtcDao
    abstract fun carDao(): CarDao
    abstract fun tripDao(): TripDao

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

val databaseModule = module {
    single {
        Room.databaseBuilder(androidContext(), EcuDatabase::class.java, "ecu.db")
            .addMigrations(MIGRATION_1_2)
            .addCallback(EcuDatabase.defaultCar)
            .build()
    }
    single { get<EcuDatabase>().sessionDao() }
    single { get<EcuDatabase>().dtcDao() }
    single { get<EcuDatabase>().carDao() }
    single { get<EcuDatabase>().tripDao() }
}

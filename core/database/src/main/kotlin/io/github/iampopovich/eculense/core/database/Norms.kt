package io.github.iampopovich.eculense.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * v12 (R-H): one norm (or engine trait) of one car from one source. [key] = NormKey name or «trait.<name>»,
 * [source] = NormSource name (USER, REFERENCE, BASELINE, ECU). GENERIC fallbacks are never stored (computed).
 * A trait keeps its value in [center] (fuel kind / O2 kind ordinal, 0/1, PID 1C value). Room, not DataStore: the
 * ECULense backup copies the database, so learned and user norms survive a restore; rows go with the car (cascade).
 */
@Entity(
    tableName = "car_norms",
    primaryKeys = ["carId", "key", "source"],
    foreignKeys = [ForeignKey(CarEntity::class, ["id"], ["carId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("carId")],
)
data class CarNormEntity(
    val carId: Long,
    val key: String,
    val source: String,
    val low: Double?,
    val high: Double?,
    val center: Double?,
    val mad: Double?,
    val sampleCount: Int,
    val confidence: Double,
    val updatedAtMs: Long,
)

@Dao
interface CarNormDao {
    @Query("SELECT * FROM car_norms WHERE carId = :carId")
    suspend fun forCar(carId: Long): List<CarNormEntity>

    @Query("SELECT * FROM car_norms WHERE carId = :carId")
    fun observeForCar(carId: Long): Flow<List<CarNormEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(rows: List<CarNormEntity>)

    @Query("DELETE FROM car_norms WHERE carId = :carId AND key = :key AND source = :source")
    suspend fun delete(carId: Long, key: String, source: String)

    @Query("DELETE FROM car_norms WHERE carId = :carId AND source = :source AND key NOT LIKE 'trait.%'")
    suspend fun deleteNorms(carId: Long, source: String)

    /** Replaces the car's learned baselines (keys that lost their sessions disappear). */
    @Transaction
    suspend fun replaceBaselines(carId: Long, rows: List<CarNormEntity>) {
        deleteNorms(carId, "BASELINE")
        put(rows)
    }
}

/** 11 → 12 (R-H): the per-car norms table; no existing row is touched. */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `car_norms` (`carId` INTEGER NOT NULL, `key` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                "`low` REAL, `high` REAL, `center` REAL, `mad` REAL, `sampleCount` INTEGER NOT NULL, `confidence` REAL NOT NULL, " +
                "`updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`carId`, `key`, `source`), " +
                "FOREIGN KEY(`carId`) REFERENCES `cars`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_car_norms_carId` ON `car_norms` (`carId`)")
    }
}

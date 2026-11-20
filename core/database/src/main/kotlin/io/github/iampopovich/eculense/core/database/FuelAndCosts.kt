package io.github.iampopovich.eculense.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * v10 (D1): one fill-up. [odometerLogId] = the odometer_log row this fill-up wrote (source FILL_UP), so editing or deleting
 * the fill-up updates that reading. [externalId] = «csv:<hash>» for imported rows (re-import does not duplicate).
 */
@Entity(
    tableName = "fuel_fillups",
    foreignKeys = [ForeignKey(CarEntity::class, ["id"], ["carId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("carId"), Index(value = ["externalId"], unique = true)],
)
data class FuelFillUpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val carId: Long,
    val atMs: Long,
    val odometerKm: Long?,
    val litres: Double,
    val pricePerL: Double?,
    val total: Double?,
    val fullTank: Boolean,
    val missedPrevious: Boolean,
    val station: String?,
    val note: String?,
    val odometerLogId: Long? = null,
    val externalId: String? = null,
)

/** v10 (D2): one expense; [category] = ExpenseCategory name; [maintenanceItemId] = the work it paid for (no FK: a deleted work keeps the money). */
@Entity(
    tableName = "expenses",
    foreignKeys = [ForeignKey(CarEntity::class, ["id"], ["carId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("carId")],
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val carId: Long,
    val atMs: Long,
    val category: String,
    val amount: Double,
    val odometerKm: Long?,
    val note: String?,
    val maintenanceItemId: Long?,
)

@Dao
interface FuelDao {
    @Query("SELECT * FROM fuel_fillups WHERE carId = :carId ORDER BY atMs DESC, id DESC")
    fun observeForCar(carId: Long): Flow<List<FuelFillUpEntity>>

    @Query("SELECT * FROM fuel_fillups WHERE carId = :carId ORDER BY atMs DESC, id DESC")
    suspend fun forCar(carId: Long): List<FuelFillUpEntity>

    @Query("SELECT * FROM fuel_fillups WHERE id = :id")
    suspend fun byId(id: Long): FuelFillUpEntity?

    @Insert suspend fun insert(item: FuelFillUpEntity): Long

    /** IGNORE + unique externalId: a repeated CSV import skips rows it already brought in (-1). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<FuelFillUpEntity>): List<Long>

    @Update suspend fun update(item: FuelFillUpEntity)

    @Query("DELETE FROM fuel_fillups WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM fuel_fillups")
    suspend fun count(): Int
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses WHERE carId = :carId ORDER BY atMs DESC, id DESC")
    fun observeForCar(carId: Long): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE carId = :carId ORDER BY atMs DESC, id DESC")
    suspend fun forCar(carId: Long): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun byId(id: Long): ExpenseEntity?

    @Insert suspend fun insert(item: ExpenseEntity): Long

    @Update suspend fun update(item: ExpenseEntity)

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Odometer rows written by fill-ups (D1): kept in sync with the fill-up. */
@Dao
interface OdometerSyncDao {
    @Query("UPDATE odometer_log SET km = :km, atMs = :atMs WHERE id = :id")
    suspend fun update(id: Long, km: Long, atMs: Long): Int

    @Query("SELECT COUNT(*) FROM odometer_log WHERE id = :id")
    suspend fun exists(id: Long): Int
}

/**
 * 9 → 10 (D1, D2): fuel log and expenses. Only CREATE TABLE: existing rows are untouched. The tank capacity (D3) needs no
 * column: cars.tankVolumeL exists since v2.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `fuel_fillups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `carId` INTEGER NOT NULL, " +
                "`atMs` INTEGER NOT NULL, `odometerKm` INTEGER, `litres` REAL NOT NULL, `pricePerL` REAL, `total` REAL, " +
                "`fullTank` INTEGER NOT NULL, `missedPrevious` INTEGER NOT NULL, `station` TEXT, `note` TEXT, `odometerLogId` INTEGER, " +
                "`externalId` TEXT, FOREIGN KEY(`carId`) REFERENCES `cars`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_fuel_fillups_carId` ON `fuel_fillups` (`carId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_fuel_fillups_externalId` ON `fuel_fillups` (`externalId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `expenses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `carId` INTEGER NOT NULL, " +
                "`atMs` INTEGER NOT NULL, `category` TEXT NOT NULL, `amount` REAL NOT NULL, `odometerKm` INTEGER, `note` TEXT, " +
                "`maintenanceItemId` INTEGER, FOREIGN KEY(`carId`) REFERENCES `cars`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_carId` ON `expenses` (`carId`)")
    }
}

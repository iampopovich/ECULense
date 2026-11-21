package io.github.iampopovich.eculense.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v13 (R-D): one module of one module scan of a car (only real links write it; LiveWriteGate in :core:data). A
 * separate table rather than a module column in dtc_snapshots: every dtc_snapshots reader (history, AI tools,
 * readiness, Car Scanner import dedup) assumes emission codes of the engine ECU, and a scan also records modules
 * without codes or without support — a row per module, codes encoded in [dtcs] by ModuleScanCodec (:core:data).
 * [scanAtMs] groups the rows of one scan. Rows go with the car (cascade).
 */
@Entity(
    tableName = "module_scans",
    foreignKeys = [ForeignKey(CarEntity::class, ["id"], ["carId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["carId", "scanAtMs"])],
)
data class ModuleScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val carId: Long,
    val scanAtMs: Long,
    /** ELM protocol number of the link (ATDPN). */
    val protocol: String,
    /** «7D1/7D9» on CAN, «0x10» on K-Line. */
    val address: String,
    /** ModuleKind name. */
    val moduleKind: String,
    /** CODES, NOT_SUPPORTED, NO_REPLY or REFUSED. */
    val result: String,
    /** ModuleDtcService name when [result] = CODES, the NRC (hex) for REFUSED. */
    val detail: String?,
    /** «code:flags;...» (flags T test failed, P pending, C confirmed, W warning lamp). */
    val dtcs: String,
)

@Dao
interface ModuleScanDao {
    @Insert
    suspend fun insert(rows: List<ModuleScanEntity>)

    /** All rows of the newest scan of [carId]. */
    @Query("SELECT * FROM module_scans WHERE carId = :carId AND scanAtMs = (SELECT MAX(scanAtMs) FROM module_scans WHERE carId = :carId) ORDER BY id")
    suspend fun latest(carId: Long): List<ModuleScanEntity>

    @Query("SELECT * FROM module_scans WHERE carId = :carId ORDER BY scanAtMs DESC, id LIMIT :limit")
    suspend fun recent(carId: Long, limit: Int): List<ModuleScanEntity>

    @Query("SELECT COUNT(*) FROM module_scans")
    suspend fun count(): Int
}

/** 12 → 13 (R-D): the module scans table; no existing row is touched. */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `module_scans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `carId` INTEGER NOT NULL, " +
                "`scanAtMs` INTEGER NOT NULL, `protocol` TEXT NOT NULL, `address` TEXT NOT NULL, `moduleKind` TEXT NOT NULL, " +
                "`result` TEXT NOT NULL, `detail` TEXT, `dtcs` TEXT NOT NULL, " +
                "FOREIGN KEY(`carId`) REFERENCES `cars`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_module_scans_carId_scanAtMs` ON `module_scans` (`carId`, `scanAtMs`)")
    }
}

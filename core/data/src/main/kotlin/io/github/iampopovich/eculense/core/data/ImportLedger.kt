package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.database.ImportDao
import io.github.iampopovich.eculense.core.database.ImportHistoryEntity
import io.github.iampopovich.eculense.core.database.ImportSnapshotEntity
import io.github.iampopovich.eculense.core.database.ImportedFileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A fully imported .brc recording: name + size + CRC-32 from the zip. */
data class ImportedFileMark(val name: String, val size: Long, val crc: Long)

/**
 * Import memory across backups (1.27): which .brc files were already fully carried over (not extracted again) and which
 * car/unit values the previous import brought (used to tell "user didn't touch it" from a manual edit).
 */
interface ImportLedger {
    suspend fun importedFile(name: String): ImportedFileMark?
    suspend fun markFile(mark: ImportedFileMark)
    suspend fun snapshot(key: String): String?
    suspend fun putSnapshot(key: String, json: String)
}

enum class ImportStatus { DONE, FAILED, CANCELLED }

/** Import history row on the «Импорт и резервные копии» screen. */
data class ImportHistoryItem(
    val id: Long = 0,
    val fileName: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val status: ImportStatus,
    val recordingsAdded: Int = 0,
    val recordingsSkipped: Int = 0,
    val tripsAdded: Int = 0,
    val tripsUpdated: Int = 0,
    val tripsSkipped: Int = 0,
    val dtcAdded: Int = 0,
    val carFieldsUpdated: Int = 0,
    val unitsUpdated: Boolean = false,
    val errors: Int = 0,
    val message: String? = null,
) {
    companion object {
        fun from(report: ImportReport, fileName: String, startedAtMs: Long, finishedAtMs: Long) = ImportHistoryItem(
            fileName = fileName, startedAtMs = startedAtMs, finishedAtMs = finishedAtMs, status = ImportStatus.DONE,
            recordingsAdded = report.imported, recordingsSkipped = report.skippedDuplicates, tripsAdded = report.tripsImported,
            tripsUpdated = report.tripsUpdated, tripsSkipped = report.tripsSkipped, dtcAdded = report.dtcEventsAdded,
            carFieldsUpdated = report.carFieldsUpdated.size,
            unitsUpdated = report.unitsImported, errors = report.failed.size,
        )
    }
}

interface ImportHistoryRepository {
    fun observe(): Flow<List<ImportHistoryItem>>
    suspend fun add(item: ImportHistoryItem)
}

internal class RoomImportLedger(private val dao: ImportDao, private val clock: () -> Long = System::currentTimeMillis) : ImportLedger, ImportHistoryRepository {
    override suspend fun importedFile(name: String): ImportedFileMark? = dao.file(name)?.let { ImportedFileMark(it.name, it.size, it.crc) }

    override suspend fun markFile(mark: ImportedFileMark) = dao.putFile(ImportedFileEntity(mark.name, mark.size, mark.crc, clock()))

    override suspend fun snapshot(key: String): String? = dao.snapshot(key)

    override suspend fun putSnapshot(key: String, json: String) = dao.putSnapshot(ImportSnapshotEntity(key, json))

    override fun observe(): Flow<List<ImportHistoryItem>> = dao.observeHistory().map { list -> list.map { it.toModel() } }

    override suspend fun add(item: ImportHistoryItem) {
        dao.insertHistory(
            ImportHistoryEntity(
                source = SOURCE_CARSCANNER, fileName = item.fileName, startedAtMs = item.startedAtMs, finishedAtMs = item.finishedAtMs,
                status = item.status.name, recordingsAdded = item.recordingsAdded, recordingsSkipped = item.recordingsSkipped,
                tripsAdded = item.tripsAdded, tripsUpdated = item.tripsUpdated, tripsSkipped = item.tripsSkipped, dtcAdded = item.dtcAdded,
                carFieldsUpdated = item.carFieldsUpdated, unitsUpdated = item.unitsUpdated, errors = item.errors, message = item.message,
            ),
        )
    }

    private fun ImportHistoryEntity.toModel() = ImportHistoryItem(
        id = id, fileName = fileName, startedAtMs = startedAtMs, finishedAtMs = finishedAtMs,
        status = runCatching { ImportStatus.valueOf(status) }.getOrDefault(ImportStatus.FAILED),
        recordingsAdded = recordingsAdded, recordingsSkipped = recordingsSkipped, tripsAdded = tripsAdded, tripsUpdated = tripsUpdated,
        tripsSkipped = tripsSkipped, dtcAdded = dtcAdded, carFieldsUpdated = carFieldsUpdated, unitsUpdated = unitsUpdated,
        errors = errors, message = message,
    )

    private companion object {
        const val SOURCE_CARSCANNER = "CARSCANNER"
    }
}

/** Without a database (unit tests, single recording): remembers nothing between instances. */
class InMemoryImportLedger : ImportLedger, ImportHistoryRepository {
    val files = mutableMapOf<String, ImportedFileMark>()
    val snapshots = mutableMapOf<String, String>()
    val history = kotlinx.coroutines.flow.MutableStateFlow<List<ImportHistoryItem>>(emptyList())
    override suspend fun importedFile(name: String) = files[name]
    override suspend fun markFile(mark: ImportedFileMark) { files[mark.name] = mark }
    override suspend fun snapshot(key: String) = snapshots[key]
    override suspend fun putSnapshot(key: String, json: String) { snapshots[key] = json }
    override fun observe(): Flow<List<ImportHistoryItem>> = history
    override suspend fun add(item: ImportHistoryItem) { history.value = listOf(item.copy(id = history.value.size + 1L)) + history.value }
}

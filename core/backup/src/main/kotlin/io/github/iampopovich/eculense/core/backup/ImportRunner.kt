package io.github.iampopovich.eculense.core.backup

import io.github.iampopovich.eculense.core.data.ImportHistoryItem
import io.github.iampopovich.eculense.core.data.ImportHistoryRepository
import io.github.iampopovich.eculense.core.data.ImportProgress
import io.github.iampopovich.eculense.core.data.ImportReport
import io.github.iampopovich.eculense.core.data.ImportStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * A single import run (what the worker does, minus WorkManager): import → history row (done / failed / cancelled).
 * A cancelled import keeps the records already transferred (they are complete); a retry resumes from the same point via deduplication.
 */
class ImportRunner(
    private val importer: suspend (uri: String, fileName: String, onProgress: (ImportProgress) -> Unit) -> ImportReport,
    private val history: ImportHistoryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(uri: String, fileName: String, onProgress: (ImportProgress) -> Unit = {}): ImportHistoryItem {
        val started = clock()
        var last = ImportProgress(0, 0)
        try {
            val report = importer(uri, fileName) { last = it; onProgress(it) }
            return ImportHistoryItem.from(report, fileName, started, clock()).also { history.add(it) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                history.add(ImportHistoryItem(fileName = fileName, startedAtMs = started, finishedAtMs = clock(), status = ImportStatus.CANCELLED,
                    message = "Отменено: обработано записей ${last.done} из ${last.total}"))
            }
            throw e
        } catch (e: Exception) {
            history.add(ImportHistoryItem(fileName = fileName, startedAtMs = started, finishedAtMs = clock(), status = ImportStatus.FAILED, errors = 1,
                message = e.message ?: e.javaClass.simpleName))
            throw e
        }
    }
}

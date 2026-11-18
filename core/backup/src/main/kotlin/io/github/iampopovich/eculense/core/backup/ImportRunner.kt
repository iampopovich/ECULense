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
 * Один запуск импорта (то, что делает воркер, без WorkManager): импорт → строка истории (готово / ошибка / отменён).
 * Отменённый импорт оставляет уже перенесённые записи (они целые), повтор продолжит с того же места через дедупликацию.
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

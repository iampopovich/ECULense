package io.github.iampopovich.eculense.feature.backup

import io.github.iampopovich.eculense.core.data.ImportHistoryItem
import io.github.iampopovich.eculense.core.data.ImportStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryLineTest {
    private val base = ImportHistoryItem(fileName = "Тестовая.cbz", startedAtMs = 1_767_000_000_000, finishedAtMs = 1_767_000_010_000, status = ImportStatus.DONE)

    @Test
    fun `done import lists what was added and updated`() {
        val item = base.copy(recordingsAdded = 1, recordingsSkipped = 2, tripsAdded = 1, tripsUpdated = 1, dtcAdded = 1, carFieldsUpdated = 2)
        assertEquals(
            "записи +1 (уже были 2) · поездки +1, обновлено 1 · события ошибок +1 · профиль машины: обновлено полей 2",
            historyLine(item),
        )
    }

    @Test
    fun `re-import shows zeros`() {
        assertEquals("записи +0 (уже были 3) · поездки +0 · события ошибок +0", historyLine(base.copy(recordingsSkipped = 3, tripsSkipped = 3)))
    }

    @Test
    fun `failed and cancelled imports say so`() {
        assertEquals("Ошибка: битый архив", historyLine(base.copy(status = ImportStatus.FAILED, message = "битый архив")))
        assertEquals("Импорт не удался: битый архив", reportText(base.copy(status = ImportStatus.FAILED, message = "битый архив")))
        assertEquals("Отменено: обработано записей 1 из 5", historyLine(base.copy(status = ImportStatus.CANCELLED, message = "Отменено: обработано записей 1 из 5")))
    }
}

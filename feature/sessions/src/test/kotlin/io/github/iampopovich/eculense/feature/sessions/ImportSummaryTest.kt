package io.github.iampopovich.eculense.feature.sessions

import io.github.iampopovich.eculense.core.data.ImportedCar
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportSummaryTest {
    @Test
    fun `summary lists recordings, car, price, trips and units`() {
        val text = importSummary(
            ImportState.Done(
                imported = 2, duplicates = 1, failed = 0,
                car = ImportedCar("Тестовая", "Lada", 2010, 60.5, "RUB", created = false),
                tripsImported = 10, tripsSkipped = 0, unitsImported = true,
            ),
        )
        assertEquals(
            listOf(
                "Импортировано 2, пропущено дублей 1, ошибок 0",
                "Машина «Тестовая» (Lada, 2010) — обновлена",
                "Цена топлива: 60,5 RUB/л",
                "Поездки: добавлено 10, уже были 0",
                "Единицы измерения перенесены",
            ).joinToString("\n"),
            text,
        )
    }

    @Test
    fun `single brc import keeps the short line`() {
        assertEquals("Импортировано 1, пропущено дублей 0, ошибок 0", importSummary(ImportState.Done(1, 0, 0)))
    }
}

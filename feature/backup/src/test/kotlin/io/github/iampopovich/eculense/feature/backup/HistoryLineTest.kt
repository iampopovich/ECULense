package io.github.iampopovich.eculense.feature.backup

import io.github.iampopovich.eculense.core.data.ImportHistoryItem
import io.github.iampopovich.eculense.core.data.ImportStatus
import io.github.iampopovich.eculense.core.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryLineTest {
    private val base = ImportHistoryItem(fileName = "Тестовая.cbz", startedAtMs = 1_767_000_000_000, finishedAtMs = 1_767_000_010_000, status = ImportStatus.DONE)

    private fun res(id: Int, vararg args: Any) = UiText.Res(id, args.toList())

    @Test
    fun `done import lists what was added and updated`() {
        val item = base.copy(recordingsAdded = 1, recordingsSkipped = 2, tripsAdded = 1, tripsUpdated = 1, dtcAdded = 1, carFieldsUpdated = 2)
        assertEquals(
            UiText.Joined(
                listOf(
                    res(R.string.backup_line_recordings_skipped, 1, 2),
                    res(R.string.backup_line_trips_updated, 1, 1),
                    res(R.string.backup_line_dtc, 1),
                    res(R.string.backup_line_car_fields, 2),
                ),
            ),
            historyLine(item),
        )
    }

    @Test
    fun `re-import shows zeros`() {
        assertEquals(
            listOf(res(R.string.backup_line_recordings_skipped, 0, 3), res(R.string.backup_line_trips, 0), res(R.string.backup_line_dtc, 0)),
            historyParts(base.copy(recordingsSkipped = 3, tripsSkipped = 3)),
        )
    }

    @Test
    fun `units and errors are listed when present`() {
        val parts = historyParts(base.copy(unitsUpdated = true, errors = 4))
        assertEquals(res(R.string.backup_line_units), parts[3])
        assertEquals(res(R.string.backup_line_errors, 4), parts[4])
    }

    @Test
    fun `failed and cancelled imports say so`() {
        val failed = base.copy(status = ImportStatus.FAILED, message = "broken archive")
        assertEquals(res(R.string.backup_line_failed, "broken archive"), historyLine(failed))
        assertEquals(res(R.string.backup_report_failed, "broken archive"), reportText(failed))
        // the worker's own cancel message is shown as is
        val cancelled = base.copy(status = ImportStatus.CANCELLED, message = "Cancelled: 1 of 5")
        assertEquals(UiText.Raw("Cancelled: 1 of 5"), historyLine(cancelled))
        assertEquals(res(R.string.backup_report_cancelled), reportText(base.copy(status = ImportStatus.CANCELLED)))
        assertEquals(res(R.string.backup_report_done, historyLine(base)), reportText(base))
    }
}

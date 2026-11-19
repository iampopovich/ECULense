package io.github.iampopovich.eculense.feature.backup

import io.github.iampopovich.eculense.core.backup.BackupProblem
import io.github.iampopovich.eculense.core.data.ImportMessage
import io.github.iampopovich.eculense.core.ui.UiText

/** Typed backup/import outcomes from :core:backup and :core:data in the app language; technical texts stay raw. */
internal object BackupTexts {
    fun problem(p: BackupProblem): UiText = when (p) {
        BackupProblem.NotEculense -> UiText.Res(R.string.backup_rejected_not_eculense)
        BackupProblem.UnknownFormat -> UiText.Res(R.string.backup_rejected_unknown_format)
        is BackupProblem.NewerVersion -> UiText.Res(R.string.backup_rejected_newer, listOf(p.backupSchema, p.appSchema))
        BackupProblem.NoDatabase -> UiText.Res(R.string.backup_rejected_no_database)
        BackupProblem.VersionMismatch -> UiText.Res(R.string.backup_rejected_version_mismatch)
        is BackupProblem.BadSettings -> UiText.Res(R.string.backup_rejected_bad_settings, listOf(p.name))
        BackupProblem.NotSqlite -> UiText.Res(R.string.backup_rejected_not_sqlite)
        BackupProblem.CannotCreateFile -> UiText.Res(R.string.backup_file_create_failed)
        BackupProblem.CannotOpenFile -> UiText.Res(R.string.backup_file_unavailable)
    }

    fun message(m: ImportMessage): UiText = when (m) {
        is ImportMessage.Cancelled -> UiText.Res(R.string.backup_cancelled_progress, listOf(m.done, m.total))
        ImportMessage.FileUnavailable -> UiText.Res(R.string.backup_file_unavailable)
        is ImportMessage.Text -> UiText.Raw(m.value)
    }

    fun message(stored: String): UiText = message(ImportMessage.parse(stored))
}

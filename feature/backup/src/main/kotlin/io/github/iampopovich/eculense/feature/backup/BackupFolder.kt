package io.github.iampopovich.eculense.feature.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/** The most recent .cbz in the Car Scanner backup folder (by modification date). */
data class CbzCandidate(val uri: String, val name: String, val lastModifiedMs: Long)

/** SAF folder with persistent access — an interface so the ViewModel can be tested without ContentResolver. */
interface BackupFolder {
    fun displayName(treeUri: String): String?
    fun newest(treeUri: String): CbzCandidate?
}

internal class SafBackupFolder(private val context: Context) : BackupFolder {
    override fun displayName(treeUri: String): String? = runCatching {
        val tree = Uri.parse(treeUri)
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    override fun newest(treeUri: String): CbzCandidate? = runCatching {
        val tree = Uri.parse(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        context.contentResolver.query(children, columns, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (!name.endsWith(".cbz", ignoreCase = true)) continue
                    add(CbzCandidate(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString(), name, c.getLong(2)))
                }
            }.maxByOrNull { it.lastModifiedMs }
        }
    }.getOrNull()
}

/** Name of the selected file: by extension the importer tells a single .brc recording from a .cbz backup. */
internal fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

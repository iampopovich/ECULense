package io.github.iampopovich.eculense.feature.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.backup.CompactState
import io.github.iampopovich.eculense.core.backup.StorageService
import io.github.iampopovich.eculense.core.backup.StorageSizes
import io.github.iampopovich.eculense.core.backup.BackupManifest
import io.github.iampopovich.eculense.core.backup.BackupPrefs
import io.github.iampopovich.eculense.core.backup.BackupRejectedException
import io.github.iampopovich.eculense.core.backup.BackupService
import io.github.iampopovich.eculense.core.backup.ImportService
import io.github.iampopovich.eculense.core.backup.ImportWorkState
import io.github.iampopovich.eculense.core.data.ImportHistoryItem
import io.github.iampopovich.eculense.core.data.ImportHistoryRepository
import io.github.iampopovich.eculense.core.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** «Резервная копия ECULense» block: what is happening now and what to show. */
internal data class CopyState(
    val busy: Boolean = false,
    val message: UiText? = null,
    /** Backup selected for restore — awaits confirmation «заменит текущие данные». */
    val pendingRestore: PendingRestore? = null,
)

internal data class PendingRestore(val uri: String, val manifest: BackupManifest)

internal data class BackupUiState(
    val folderUri: String? = null,
    val folderName: String? = null,
    val newest: CbzCandidate? = null,
    val importState: ImportWorkState = ImportWorkState.Idle,
    val history: List<ImportHistoryItem> = emptyList(),
    val includeAiChat: Boolean = false,
    val copy: CopyState = CopyState(),
    /** R3.2: sizes on the phone; null until measured or without the storage service. */
    val storage: StorageSizes? = null,
    val compact: CompactState = CompactState.Idle,
    val recording: Boolean = false,
) {
    val importing: Boolean get() = importState is ImportWorkState.Running
}

internal class BackupViewModel(
    private val imports: ImportService,
    private val history: ImportHistoryRepository,
    private val prefs: BackupPrefs,
    private val folder: BackupFolder,
    private val backup: BackupService,
    private val storage: StorageService? = null,
) : ViewModel() {
    private val folderInfo = MutableStateFlow<Pair<String?, CbzCandidate?>>(null to null)
    private val copy = MutableStateFlow(CopyState())
    private val sizes = MutableStateFlow<StorageSizes?>(null)

    private data class StorageUi(val sizes: StorageSizes?, val compact: CompactState, val recording: Boolean)

    private val storageUi: kotlinx.coroutines.flow.Flow<StorageUi> = storage?.let { s ->
        combine(sizes, s.compaction, s.recording) { z, c, r -> StorageUi(z, c, r) }
    } ?: kotlinx.coroutines.flow.flowOf(StorageUi(null, CompactState.Idle, false))

    val state: StateFlow<BackupUiState> = combine(
        imports.state, history.observe(), prefs.folderUri, prefs.includeAiChat,
        combine(folderInfo, copy, storageUi) { f, c, st -> Triple(f, c, st) },
    ) { importState, items, folderUri, aiChat, (info, copyState, st) ->
        BackupUiState(folderUri, info.first, info.second, importState, items, aiChat, copyState, st.sizes, st.compact, st.recording)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUiState())

    /** Sizes are measured on open and again after a compaction finished. */
    private fun measure() {
        val s = storage ?: return
        viewModelScope.launch { sizes.value = runCatching { s.sizes() }.getOrNull() }
    }

    fun compact() {
        storage?.compact()
    }

    init {
        measure()
        storage?.let { s -> viewModelScope.launch { s.compaction.map { it is CompactState.Done }.distinctUntilChanged().collect { if (it) measure() } } }
        // folder selected/changed or import finished — re-check which backup in the folder is the newest
        viewModelScope.launch { prefs.folderUri.distinctUntilChanged().collect { refreshFolder(it) } }
        viewModelScope.launch {
            imports.state.map { it is ImportWorkState.Finished }.distinctUntilChanged().collect { if (it) refreshFolder(prefs.folderUri.first()) }
        }
    }

    private suspend fun refreshFolder(uri: String?) {
        folderInfo.value = if (uri == null) null to null else withContext(Dispatchers.IO) { folder.displayName(uri) to folder.newest(uri) }
    }

    /** Called directly from the file picker result (app in the foreground) — the expedited job has time to become foreground. */
    fun importFile(uri: Uri, fileName: String) {
        if (state.value.importing) return
        imports.start(uri.toString(), fileName, releaseUri = true)
    }

    fun setFolder(uri: Uri) {
        viewModelScope.launch { prefs.setFolder(uri.toString()) }
    }

    fun importNewest() {
        val newest = state.value.newest ?: return
        if (state.value.importing) return
        imports.start(newest.uri, newest.name, releaseUri = false)
    }

    fun cancelImport() = imports.cancel()

    fun setIncludeAiChat(value: Boolean) {
        viewModelScope.launch { prefs.setIncludeAiChat(value) }
    }

    fun export(uri: Uri) = copyJob {
        val manifest = backup.export(uri, state.value.includeAiChat)
        val saved = UiText.Res(if ("ai_chat.db" in manifest.databases) R.string.backup_saved_with_chat else R.string.backup_saved)
        when {
            manifest.accelSkippedBytes > 0 -> UiText.Joined(
                listOf(saved, UiText.Res(R.string.backup_accel_skipped, listOf(((manifest.accelSkippedBytes + 524_288) / 1_048_576).toInt()))), " ",
            )
            manifest.accelFiles.isNotEmpty() -> UiText.Joined(listOf(saved, UiText.Res(R.string.backup_accel_included, listOf(manifest.accelFiles.size))), " ")
            else -> saved
        }
    }

    fun pickRestore(uri: Uri) = copyJob {
        val manifest = backup.inspect(uri)
        copy.update { it.copy(pendingRestore = PendingRestore(uri.toString(), manifest)) }
        null
    }

    fun dismissRestore() = copy.update { it.copy(pendingRestore = null) }

    fun confirmRestore() {
        val pending = copy.value.pendingRestore ?: return
        if (state.value.importing) return
        copy.update { it.copy(pendingRestore = null) }
        copyJob {
            backup.restore(Uri.parse(pending.uri))
            backup.restartApp()
            UiText.Res(R.string.backup_restored)
        }
    }

    fun clearMessage() = copy.update { it.copy(message = null) }

    private fun copyJob(block: suspend () -> UiText?) {
        if (copy.value.busy) return
        copy.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupRejectedException) {
                BackupTexts.problem(e.problem)
            } catch (e: Exception) {
                UiText.Raw(e.message ?: e.javaClass.simpleName)
            }
            copy.update { it.copy(busy = false, message = message ?: it.message) }
        }
    }
}

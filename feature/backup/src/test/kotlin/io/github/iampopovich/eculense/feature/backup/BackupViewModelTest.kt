package io.github.iampopovich.eculense.feature.backup

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.iampopovich.eculense.core.backup.BackupManifest
import io.github.iampopovich.eculense.core.backup.BackupPrefs
import io.github.iampopovich.eculense.core.backup.BackupProblem
import io.github.iampopovich.eculense.core.backup.BackupRejectedException
import io.github.iampopovich.eculense.core.backup.BackupService
import io.github.iampopovich.eculense.core.backup.CompactState
import io.github.iampopovich.eculense.core.backup.ImportService
import io.github.iampopovich.eculense.core.backup.ImportWorkState
import io.github.iampopovich.eculense.core.backup.RestoreResult
import io.github.iampopovich.eculense.core.backup.StorageService
import io.github.iampopovich.eculense.core.backup.StorageSizes
import io.github.iampopovich.eculense.core.data.ImportHistoryItem
import io.github.iampopovich.eculense.core.data.ImportHistoryRepository
import io.github.iampopovich.eculense.core.ui.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

private class FakeImports : ImportService {
    override val state = MutableStateFlow<ImportWorkState>(ImportWorkState.Idle)
    val started = mutableListOf<Triple<String, String, Boolean>>()
    var cancels = 0
    override fun start(uri: String, fileName: String, releaseUri: Boolean) {
        started += Triple(uri, fileName, releaseUri)
        state.value = ImportWorkState.Running(0, 0)
    }
    override fun cancel() { cancels++ }
}

private class NoHistory : ImportHistoryRepository {
    override fun observe() = flowOf(emptyList<ImportHistoryItem>())
    override suspend fun add(item: ImportHistoryItem) = Unit
}

private class FakeFolder(var newest: CbzCandidate?) : BackupFolder {
    var looks = 0
    override fun displayName(treeUri: String) = "Backups"
    override fun newest(treeUri: String): CbzCandidate? = newest.also { looks++ }
}

private class FakeBackup : BackupService {
    var manifest = manifest()
    var failure: Exception? = null
    val restored = mutableListOf<Uri>()
    var restarts = 0
    var exports = mutableListOf<Boolean>()
    val gate = CompletableDeferred<Unit>().apply { complete(Unit) }
    override suspend fun export(uri: Uri, includeAiChat: Boolean): BackupManifest {
        exports += includeAiChat
        failure?.let { throw it }
        return manifest
    }
    override suspend fun inspect(uri: Uri): BackupManifest = failure?.let { throw it } ?: manifest
    override suspend fun restore(uri: Uri): RestoreResult {
        failure?.let { throw it }
        restored += uri
        return RestoreResult(manifest, null)
    }
    override fun restartApp() { restarts++ }

    companion object {
        fun manifest(databases: List<String> = listOf("ecu.db"), accel: List<String> = emptyList(), skipped: Long = 0) = BackupManifest(
            format = 1, app = "ECULense", appVersion = "0.2.0-test", schemaVersion = 13, createdAtMs = 1_000L, databases = databases,
            datastore = emptyList(), secretsExcluded = 0, accelFiles = accel, accelSkippedBytes = skipped,
        )
    }
}

private class FakeStorage : StorageService {
    var measures = 0
    var fail = false
    override suspend fun sizes(): StorageSizes {
        measures++
        if (fail) error("no access")
        return StorageSizes(1_000L * measures, 10L, 20L)
    }
    override val compaction = MutableStateFlow<CompactState>(CompactState.Idle)
    override val recording = MutableStateFlow(false)
    var compacts = 0
    override fun compact() { compacts++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private val imports = FakeImports()
    private val backup = FakeBackup()
    private val storage = FakeStorage()
    private val folder = FakeFolder(CbzCandidate("content://tree/backups/doc/b.cbz", "test-backup.cbz", 1_000L))
    private val uri: Uri = Uri.parse("content://test/backup.eculense")

    private fun prefs() = BackupPrefs(PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "backup.preferences_pb") }))

    private fun vm(prefs: BackupPrefs = prefs(), storage: StorageService? = this.storage) = BackupViewModel(imports, NoHistory(), prefs, folder, backup, storage).also { vm ->
        scope.launch(Dispatchers.Unconfined) { vm.state.collect {} }
    }

    private fun await(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(what, condition())
    }

    @Test
    fun `a picked file starts one import at a time, cancel goes to the service`() {
        val vm = vm()
        vm.importFile(Uri.parse("content://test/a.cbz"), "a.cbz")
        await("running") { vm.state.value.importing }
        vm.importFile(Uri.parse("content://test/b.cbz"), "b.cbz") // ignored while running
        assertEquals(listOf(Triple("content://test/a.cbz", "a.cbz", true)), imports.started)
        vm.cancelImport()
        assertEquals(1, imports.cancels)
    }

    @Test
    fun `a chosen folder shows its newest backup, imports it keeping the folder access, and re-checks after an import`() = runBlocking {
        val prefs = prefs()
        val vm = vm(prefs)
        vm.importNewest() // no folder yet: nothing
        assertTrue(imports.started.isEmpty())
        prefs.setFolder("content://tree/backups")
        await("folder read") { vm.state.value.newest != null }
        assertEquals("Backups", vm.state.value.folderName)
        vm.importNewest()
        assertEquals(Triple("content://tree/backups/doc/b.cbz", "test-backup.cbz", false), imports.started.single())
        val looks = folder.looks
        imports.state.value = ImportWorkState.Finished(ok = true)
        await("folder re-checked") { folder.looks > looks }
    }

    @Test
    fun `export says what went in, the AI chat switch is passed on`() = runBlocking {
        val prefs = prefs()
        val vm = vm(prefs)
        prefs.setIncludeAiChat(true)
        await("switch") { vm.state.value.includeAiChat }
        backup.manifest = FakeBackup.manifest(databases = listOf("ecu.db", "ai_chat.db"), accel = listOf("1.bin", "2.bin"))
        vm.export(uri)
        await("message") { vm.state.value.copy.message != null }
        assertEquals(listOf(true), backup.exports)
        assertEquals(
            UiText.Joined(listOf(UiText.Res(R.string.backup_saved_with_chat), UiText.Res(R.string.backup_accel_included, listOf(2))), " "),
            vm.state.value.copy.message,
        )
        assertFalse(vm.state.value.copy.busy)
        vm.clearMessage()
        backup.manifest = FakeBackup.manifest(skipped = 60L * 1_048_576)
        vm.export(uri)
        await("skipped message") { vm.state.value.copy.message != null }
        assertEquals(
            UiText.Joined(listOf(UiText.Res(R.string.backup_saved), UiText.Res(R.string.backup_accel_skipped, listOf(60))), " "),
            vm.state.value.copy.message,
        )
    }

    @Test
    fun `restore asks first, then restores and restarts, dismiss keeps the data`() {
        val vm = vm()
        vm.pickRestore(uri)
        await("pending") { vm.state.value.copy.pendingRestore != null }
        assertEquals(uri.toString(), vm.state.value.copy.pendingRestore!!.uri)
        assertTrue(backup.restored.isEmpty())
        vm.dismissRestore()
        assertNull(vm.state.value.copy.pendingRestore)
        vm.confirmRestore() // nothing pending: no-op
        assertTrue(backup.restored.isEmpty())

        vm.pickRestore(uri)
        await("pending again") { vm.state.value.copy.pendingRestore != null && !vm.state.value.copy.busy }
        vm.confirmRestore()
        await("restored") { vm.state.value.copy.message != null }
        assertEquals(listOf(uri), backup.restored)
        assertEquals(1, backup.restarts)
        assertEquals(UiText.Res(R.string.backup_restored), vm.state.value.copy.message)
    }

    @Test
    fun `a rejected backup shows the problem, other errors their message, nothing is restored`() {
        val vm = vm()
        backup.failure = BackupRejectedException(BackupProblem.NewerVersion(backupSchema = 99, appSchema = 13))
        vm.pickRestore(uri)
        await("rejected") { vm.state.value.copy.message != null }
        assertEquals(UiText.Res(R.string.backup_rejected_newer, listOf(99, 13)), vm.state.value.copy.message)
        assertNull(vm.state.value.copy.pendingRestore)
        vm.clearMessage()
        backup.failure = IllegalStateException("disk full")
        vm.export(uri)
        await("error") { vm.state.value.copy.message != null }
        assertEquals(UiText.Raw("disk full"), vm.state.value.copy.message)
        assertFalse(vm.state.value.copy.busy)
        assertTrue(backup.restored.isEmpty() && backup.restarts == 0)
    }

    @Test
    fun `storage sizes are measured on open and after a compaction, recording and the refusal come through`() {
        val vm = vm()
        await("measured") { vm.state.value.storage != null }
        assertEquals(1_000L, vm.state.value.storage!!.databaseBytes)
        vm.compact()
        assertEquals(1, storage.compacts)
        storage.recording.value = true
        await("recording") { vm.state.value.recording }
        storage.compaction.value = CompactState.Recording // the job refused: a recording is running
        await("refused") { vm.state.value.compact == CompactState.Recording }
        storage.compaction.value = CompactState.Done(1_000L, 500L)
        await("re-measured") { storage.measures == 2 && vm.state.value.storage?.databaseBytes == 2_000L }
    }

    @Test
    fun `without the storage service or when measuring fails there are no sizes`() {
        val none = vm(storage = null)
        Thread.sleep(100)
        assertNull(none.state.value.storage)
        assertEquals(CompactState.Idle, none.state.value.compact)
        none.compact() // no-op
        storage.fail = true
        val failing = vm()
        await("tried") { storage.measures >= 1 }
        Thread.sleep(50)
        assertNull(failing.state.value.storage)
    }
}

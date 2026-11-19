package io.github.iampopovich.eculense.core.ailocal

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.datapacks.DataPacks
import io.github.iampopovich.eculense.core.datapacks.PackGroup
import io.github.iampopovich.eculense.core.datapacks.PackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A model row on the screen: pre-download estimate, download, test, selection. */
internal data class ModelRow(
    val model: LocalModel,
    val fit: FitEstimate,
    val installed: Boolean,
    /** null — not downloading; -1 — downloading, percentage unknown. */
    val progress: Int? = null,
    val downloadedBytes: Long = 0,
    val retrying: Boolean = false,
    val error: String? = null,
    val report: BenchReport? = null,
    /** Backend the model runs on in chat; null — model not selected. */
    val activeBackend: BackendKind? = null,
    /** This model is being tested right now: on which backend. */
    val benchingBackend: BackendKind? = null,
    val benchDone: List<BackendRun> = emptyList(),
) {
    val downloading: Boolean get() = progress != null
    val benching: Boolean get() = benchDone.isNotEmpty() || benchingBackend != null
    val partial: Boolean get() = !installed && !downloading && downloadedBytes > 0
}

/** Download confirmation: at your own risk, with an estimate and a mobile-data warning. [adopt] — a model from HF search that must first be added to the list. */
internal data class DownloadConfirm(val packId: String, val title: String, val sizeBytes: Long?, val fit: FitEstimate, val metered: Boolean, val gatedWithoutToken: Boolean, val adopt: LocalModel? = null)

internal data class LocalModelUiState(
    val device: DeviceProfile? = null,
    val nano: NanoStatus? = null,
    /** Unified list (Koog + Gallery + custom), by size from smallest to largest. */
    val rows: List<ModelRow> = emptyList(),
    val catalogVersion: String = GalleryAllowlist.BUNDLED_VERSION,
    val catalogRefreshing: Boolean = false,
    val searchQuery: String = "",
    val searchBusy: Boolean = false,
    val searchError: String? = null,
    /** null — no search was done. */
    val searchResults: List<ModelRow>? = null,
    /** Model opened in the bottom details sheet. */
    val selected: String? = null,
    val hasToken: Boolean = false,
    val tokenInput: String = "",
    val linkInput: String = "",
    val linkBusy: Boolean = false,
    val linkError: String? = null,
    val importProgress: Long? = null,
    val confirm: DownloadConfirm? = null,
    val message: String? = null,
)

internal class LocalModelViewModel(
    private val models: LocalModels,
    private val packs: DataPacks,
    private val store: LocalModelStore,
    private val bench: LocalBench,
    private val runtime: LocalLlmRuntime,
    private val probe: DeviceProbe,
    private val nano: NanoProbe,
) : ViewModel() {
    private val _state = MutableStateFlow(LocalModelUiState())
    val state: StateFlow<LocalModelUiState> = _state.asStateFlow()

    /** Models whose test has already run on this screen (auto-run happens once). */
    private val autoBenched = mutableSetOf<String>()

    init {
        refreshDevice()
        _state.update { it.copy(catalogVersion = models.catalogVersion) }
        // Gallery catalog and gated status are fetched on demand (when the screen opens); offline, the saved/bundled one stays.
        refreshCatalog(silent = true)
        viewModelScope.launch {
            val device = _state.value.device ?: probe.profile()
            _state.update { it.copy(nano = nano.status(device.aicore)) }
        }
        viewModelScope.launch {
            combine(models.models, packs.observe(PackGroup.MODEL), store.reports, store.active, bench.progress) { list, packStates, reports, active, progress ->
                Snapshot(list, packStates.associateBy { it.manifest.id }, reports, active, progress)
            }.combine(store.hasToken) { s, token -> s to token }.collect { (s, token) ->
                val device = _state.value.device ?: probe.profile()
                val rows = s.models.map { m -> row(m, s, device) }
                _state.update { it.copy(rows = rows, hasToken = token) }
                autoBench(rows, s.progress)
            }
        }
    }

    private data class Snapshot(
        val models: List<LocalModel>,
        val packs: Map<String, PackState>,
        val reports: Map<String, BenchReport>,
        val active: ActiveModel?,
        val progress: BenchProgress?,
    )

    private fun row(m: LocalModel, s: Snapshot, device: DeviceProfile): ModelRow {
        val pack = s.packs[m.packId]
        val installed = pack?.installed != null || (m.origin == ModelOrigin.CUSTOM_FILE && models.file(m.packId) != null)
        val benching = s.progress?.takeIf { it.packId == m.packId }
        return ModelRow(
            model = m,
            fit = FitEstimator.estimate(m.sizeBytes, device, downloaded = installed, minRamGb = m.minRamGb),
            installed = installed,
            progress = pack?.progress,
            downloadedBytes = pack?.downloadedBytes ?: 0,
            retrying = pack?.retrying == true,
            error = pack?.error,
            report = s.reports[m.packId],
            activeBackend = s.active?.takeIf { it.packId == m.packId }?.backend,
            benchingBackend = benching?.current,
            benchDone = benching?.done.orEmpty(),
        )
    }

    /** Speed test right after download (plan 3.10). */
    private fun autoBench(rows: List<ModelRow>, progress: BenchProgress?) {
        if (progress != null) return
        val next = rows.firstOrNull { it.installed && it.report == null && it.model.packId !in autoBenched } ?: return
        autoBenched += next.model.packId
        bench.start(next.model.packId)
    }

    fun refreshDevice() {
        _state.update { it.copy(device = probe.profile()) }
    }

    /** «Скачать»: always allowed — but first a «на свой риск» warning (+ mobile data). */
    fun requestDownload(packId: String) {
        val found = _state.value.rows.firstOrNull { it.model.packId == packId }
        val searched = if (found == null) _state.value.searchResults?.firstOrNull { it.model.packId == packId } else null
        val row = found ?: searched ?: return
        if (row.model.url == null) return
        _state.update {
            it.copy(
                confirm = DownloadConfirm(
                    packId, row.model.title, row.model.sizeBytes, row.fit,
                    metered = packs.isMeteredNetwork(),
                    gatedWithoutToken = row.model.gated && !it.hasToken,
                    adopt = searched?.model,
                ),
            )
        }
    }

    fun confirmDownload() {
        val c = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        viewModelScope.launch {
            // A model from HF search becomes a custom one (otherwise the downloader doesn't know the pack) and leaves the search results.
            c.adopt?.let { m ->
                models.add(m)
                _state.update { s -> s.copy(searchResults = s.searchResults?.filterNot { it.model.packId == m.packId }) }
            }
            // Tap in the open app: the download starts while the app is on screen (Android 14+ doesn't allow
            // a background process to start a foreground service).
            packs.download(c.packId)
            refreshDevice()
        }
    }

    fun refreshCatalog(silent: Boolean = false) {
        if (_state.value.catalogRefreshing) return
        _state.update { it.copy(catalogRefreshing = true) }
        viewModelScope.launch {
            val v = runCatching { models.refreshCatalog() }.getOrNull()
            _state.update { it.copy(catalogRefreshing = false, catalogVersion = models.catalogVersion, message = if (v == null && !silent) "Не удалось обновить каталог — показан сохранённый" else it.message) }
        }
    }

    fun setSearchQuery(value: String) = _state.update { it.copy(searchQuery = value, searchError = null) }

    fun search() {
        val q = _state.value.searchQuery.trim()
        if (q.isEmpty() || _state.value.searchBusy) return
        _state.update { it.copy(searchBusy = true, searchError = null) }
        viewModelScope.launch {
            val device = _state.value.device ?: probe.profile()
            runCatching { models.search(q) }
                .onSuccess { list ->
                    val rows = list.map { m -> ModelRow(m, FitEstimator.estimate(m.sizeBytes, device), installed = false) }
                    _state.update { it.copy(searchBusy = false, searchResults = rows) }
                }
                .onFailure { e -> _state.update { it.copy(searchBusy = false, searchError = "Поиск не удался: ${e.message ?: "нет связи с Hugging Face"}") } }
        }
    }

    fun select(packId: String?) = _state.update { it.copy(selected = packId) }

    fun dismissConfirm() = _state.update { it.copy(confirm = null) }

    fun cancelDownload(packId: String) = packs.cancel(packId)

    fun delete(packId: String) {
        viewModelScope.launch {
            if (store.active.first()?.packId == packId) runtime.release()
            models.delete(packId)
            autoBenched -= packId
            refreshDevice()
            _state.update { it.copy(message = "Модель удалена") }
        }
    }

    fun runBench(packId: String) {
        autoBenched += packId
        bench.start(packId)
    }

    /** Select a model for chat: backend is the best by test (no test — CPU). */
    fun use(packId: String) {
        viewModelScope.launch {
            val report = store.reports.first()[packId]
            val backend = report?.best?.backend ?: BackendKind.CPU
            runtime.release()
            store.activate(packId, backend)
            _state.update { it.copy(message = "Чат ИИ теперь работает на телефоне (${backend.label})") }
        }
    }

    fun useBackend(packId: String, backend: BackendKind) {
        viewModelScope.launch {
            runtime.release()
            store.activate(packId, backend)
        }
    }

    /** «Не использовать»: chat goes through the cloud provider again (if configured). */
    fun stopUsing() {
        viewModelScope.launch {
            runtime.deactivate()
            _state.update { it.copy(message = "Модель на телефоне больше не используется в чате") }
        }
    }

    fun setTokenInput(value: String) = _state.update { it.copy(tokenInput = value) }

    fun saveToken() {
        val token = _state.value.tokenInput.trim()
        if (token.isEmpty()) return
        viewModelScope.launch {
            store.setToken(token)
            _state.update { it.copy(tokenInput = "", message = "Токен Hugging Face сохранён") }
        }
    }

    fun deleteToken() {
        viewModelScope.launch {
            store.setToken(null)
            _state.update { it.copy(message = "Токен удалён") }
        }
    }

    fun setLinkInput(value: String) = _state.update { it.copy(linkInput = value, linkError = null) }

    fun addLink() {
        val link = _state.value.linkInput
        if (link.isBlank() || _state.value.linkBusy) return
        _state.update { it.copy(linkBusy = true, linkError = null) }
        viewModelScope.launch {
            when (val r = models.checkLink(link)) {
                is LinkCheck.Ok -> {
                    models.add(r.model)
                    _state.update { it.copy(linkBusy = false, linkInput = "", message = "Модель «${r.model.title}» добавлена в список") }
                }
                is LinkCheck.Failed -> _state.update { it.copy(linkBusy = false, linkError = r.message) }
            }
        }
    }

    fun importFile(uri: Uri, name: String, size: Long?) {
        if (_state.value.importProgress != null) return
        _state.update { it.copy(importProgress = 0, linkError = null) }
        viewModelScope.launch {
            runCatching { models.importFile(uri, name, size) { done -> _state.update { s -> s.copy(importProgress = done) } } }
                .onSuccess { m -> _state.update { it.copy(importProgress = null, message = "Модель «${m.title}» скопирована") } }
                .onFailure { e -> _state.update { it.copy(importProgress = null, linkError = "Не удалось скопировать файл: ${e.message}") } }
            refreshDevice()
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}

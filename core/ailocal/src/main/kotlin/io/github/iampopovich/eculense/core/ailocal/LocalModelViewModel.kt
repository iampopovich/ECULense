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

/** Строка модели на экране: оценка до скачивания, загрузка, тест, выбор. */
internal data class ModelRow(
    val model: LocalModel,
    val fit: FitEstimate,
    val installed: Boolean,
    /** null — не качается; -1 — качается, процент неизвестен. */
    val progress: Int? = null,
    val downloadedBytes: Long = 0,
    val retrying: Boolean = false,
    val error: String? = null,
    val report: BenchReport? = null,
    /** Бэкенд, на котором модель работает в чате; null — модель не выбрана. */
    val activeBackend: BackendKind? = null,
    /** Сейчас идёт тест этой модели: какой бэкенд. */
    val benchingBackend: BackendKind? = null,
    val benchDone: List<BackendRun> = emptyList(),
) {
    val downloading: Boolean get() = progress != null
    val benching: Boolean get() = benchDone.isNotEmpty() || benchingBackend != null
    val partial: Boolean get() = !installed && !downloading && downloadedBytes > 0
}

/** Подтверждение скачивания: на свой риск, с оценкой и предупреждением о мобильной сети. */
internal data class DownloadConfirm(val packId: String, val title: String, val sizeBytes: Long?, val fit: FitEstimate, val metered: Boolean, val gatedWithoutToken: Boolean)

internal data class LocalModelUiState(
    val device: DeviceProfile? = null,
    val nano: NanoStatus? = null,
    val rows: List<ModelRow> = emptyList(),
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

    /** Модели, для которых тест уже запускался в этом экране (автозапуск — один раз). */
    private val autoBenched = mutableSetOf<String>()

    init {
        refreshDevice()
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
            fit = FitEstimator.estimate(m.sizeBytes, device, downloaded = installed),
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

    /** Тест скорости сразу после скачивания (план 3.10). */
    private fun autoBench(rows: List<ModelRow>, progress: BenchProgress?) {
        if (progress != null) return
        val next = rows.firstOrNull { it.installed && it.report == null && it.model.packId !in autoBenched } ?: return
        autoBenched += next.model.packId
        bench.start(next.model.packId)
    }

    fun refreshDevice() {
        _state.update { it.copy(device = probe.profile()) }
    }

    /** «Скачать»: всегда можно — но сначала предупреждение «на свой риск» (+ мобильная сеть). */
    fun requestDownload(packId: String) {
        val row = _state.value.rows.firstOrNull { it.model.packId == packId } ?: return
        if (row.model.url == null) return
        _state.update {
            it.copy(
                confirm = DownloadConfirm(
                    packId, row.model.title, row.model.sizeBytes, row.fit,
                    metered = packs.isMeteredNetwork(),
                    gatedWithoutToken = row.model.gated && !it.hasToken,
                ),
            )
        }
    }

    fun confirmDownload() {
        val c = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        packs.download(c.packId)
        refreshDevice()
    }

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

    /** Выбрать модель для чата: бэкенд — лучший по тесту (без теста — CPU). */
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

    /** «Не использовать»: чат снова идёт через облачного провайдера (если он настроен). */
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

package io.github.iampopovich.eculense.feature.aisettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.ai.ConnectionCheck
import io.github.iampopovich.eculense.core.ai.ConnectionChecker
import io.github.iampopovich.eculense.core.ai.EndpointUrl
import io.github.iampopovich.eculense.core.ai.LlmProviderKind
import io.github.iampopovich.eculense.core.ai.LlmSettingsStore
import io.github.iampopovich.eculense.core.ai.ModelInfo
import io.github.iampopovich.eculense.core.ai.OnDeviceLlm
import io.github.iampopovich.eculense.core.ai.OnDeviceSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface CheckState {
    data object Idle : CheckState
    data object Checking : CheckState

    /** [listed] = false — свой сервер не отдал список, модель вводится вручную. */
    data class Passed(val models: List<ModelInfo>, val listed: Boolean, val message: String) : CheckState
    data class Failed(val message: String) : CheckState
}

internal data class SavedConfig(val provider: LlmProviderKind, val model: String, val baseUrl: String, val hasKey: Boolean)

/**
 * Мастер подключения: провайдер → адрес + ключ → проверка (доказывает ключ и грузит каталог) → модель → сохранить.
 * Шаг «Модель» открыт только после успешной проверки; любая правка адреса/ключа/провайдера её сбрасывает.
 */
internal data class AiSettingsUiState(
    val loaded: Boolean = false,
    val saved: SavedConfig? = null,
    val editing: Boolean = true,
    val provider: LlmProviderKind = LlmProviderKind.DEFAULT,
    /** Адрес вводится только у «Другой (свой сервер)»; у пресетов его задаёт Koog. */
    val baseUrl: String = "",
    val apiKey: String = "",
    val urlError: String? = null,
    val check: CheckState = CheckState.Idle,
    val query: String = "",
    val model: String = "",
    val busy: Boolean = false,
    /** Модуль «На устройстве» подключён — в списке провайдеров есть пункт, ведущий на его экран. */
    val onDeviceAvailable: Boolean = false,
    /** Облачная сборка: вместо пункта «На устройстве» — приглушённая строка «доступно в сборке «Локально»». */
    val onDeviceInOtherBuild: Boolean = false,
    /** Выбрана модель на телефоне — чат идёт через неё (облачная настройка, если есть, не активна). */
    val onDevice: OnDeviceSummary? = null,
) {
    /** Пустое поле ключа = использовать сохранённый (только для того же провайдера). */
    val canReuseSavedKey: Boolean get() = saved?.hasKey == true && saved.provider == provider
    /** У Ollama ключа нет — поле не показываем. */
    val showKeyField: Boolean get() = provider.usesKey
    /** Поле адреса — только у «Другой (свой сервер)». */
    val showUrlField: Boolean get() = provider == LlmProviderKind.OTHER
    val modelStepEnabled: Boolean get() = check is CheckState.Passed
    val manualModel: Boolean get() = (check as? CheckState.Passed)?.listed == false
    val canSave: Boolean get() = check is CheckState.Passed && model.isNotBlank() && !busy

    val visibleModels: List<ModelInfo>
        get() {
            val models = (check as? CheckState.Passed)?.models.orEmpty()
            val q = query.trim().lowercase()
            if (q.isEmpty()) return models
            return models.filter { q in it.id.lowercase() || q in it.name.lowercase() }
        }
}

internal class AiSettingsViewModel(
    private val store: LlmSettingsStore,
    private val checker: ConnectionChecker,
    /** Модель на телефоне (:core:ailocal), если модуль подключён. */
    private val onDevice: OnDeviceLlm? = null,
    onDeviceRoute: Boolean = false,
    /** Флаг флейвора сборки (BuildCapabilities): локальные модели есть только в сборке «Локально». */
    onDeviceInOtherBuild: Boolean = false,
) : ViewModel() {
    private val _state = MutableStateFlow(
        AiSettingsUiState(onDeviceAvailable = onDeviceRoute && onDevice != null, onDeviceInOtherBuild = onDeviceInOtherBuild && onDevice == null),
    )
    val state: StateFlow<AiSettingsUiState> = _state.asStateFlow()
    private var checkJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = store.settings.first()?.let { SavedConfig(it.provider, it.model, it.baseUrl, it.hasKey) }
            val local = onDevice?.summary?.first()
            _state.update { it.copy(loaded = true, saved = saved, editing = saved == null && local == null, onDevice = local) }
            onDevice?.summary?.collect { s -> _state.update { it.copy(onDevice = s) } }
        }
    }

    /** «Перейти на облачного провайдера»: локальная модель перестаёт использоваться, файлы остаются. */
    fun useCloud() {
        viewModelScope.launch {
            onDevice?.deactivate()
            _state.update { it.copy(onDevice = null, editing = it.saved == null) }
        }
    }

    /** Сброс проверки и выбора модели: всё, что было доказано, относилось к старым адресу/ключу. */
    private fun AiSettingsUiState.invalidated() = copy(check = CheckState.Idle, model = "", query = "", urlError = null)

    private fun edit(transform: AiSettingsUiState.() -> AiSettingsUiState) {
        checkJob?.cancel()
        _state.update { it.transform().invalidated() }
    }

    fun selectProvider(provider: LlmProviderKind) {
        if (provider == _state.value.provider) return
        edit { copy(provider = provider, baseUrl = "", apiKey = "") }
    }

    fun setBaseUrl(value: String) = edit { copy(baseUrl = value) }

    fun setApiKey(value: String) = edit { copy(apiKey = value) }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun selectModel(id: String) = _state.update { if (it.modelStepEnabled) it.copy(model = id.trim()) else it }

    fun check() {
        val s = _state.value
        if (s.check is CheckState.Checking) return
        // Адрес вводится только для «Другого»; у пресетов его задаёт Koog.
        val url = if (s.provider != LlmProviderKind.OTHER) {
            null
        } else {
            when (val v = EndpointUrl.validate(s.baseUrl)) {
                is EndpointUrl.Result.Invalid -> {
                    _state.update { it.copy(urlError = v.reason, check = CheckState.Idle) }
                    return
                }
                is EndpointUrl.Result.Valid -> v.url
            }
        }
        // Модель, выбранная до проверки (например, сохранённая при «Изменить»), остаётся, если сервер её отдаёт.
        val previousModel = s.model.ifBlank { s.saved?.takeIf { it.provider == s.provider }?.model.orEmpty() }
        _state.update { it.copy(urlError = null, check = CheckState.Checking, model = "") }
        checkJob = viewModelScope.launch {
            val key = if (!s.provider.usesKey) {
                null
            } else {
                s.apiKey.trim().ifBlank { if (s.canReuseSavedKey) store.apiKey().orEmpty() else "" }.ifBlank { null }
            }
            val result = checker.check(s.provider, url, key)
            _state.update { current ->
                val next = result.toCheckState(s.provider)
                val keep = (next as? CheckState.Passed)?.let { p -> previousModel.takeIf { m -> m.isNotBlank() && (!p.listed || p.models.any { it.id == m }) } }
                current.copy(
                    check = next,
                    model = keep.orEmpty(),
                    urlError = (result as? ConnectionCheck.InvalidUrl)?.reason,
                )
            }
        }
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val url = if (s.provider == LlmProviderKind.OTHER) (EndpointUrl.validate(s.baseUrl) as EndpointUrl.Result.Valid).url else null
            store.save(s.provider, s.model, url, s.apiKey)
            // сохранили облачного провайдера — значит, выбрали его вместо модели на телефоне
            onDevice?.deactivate()
            val saved = store.settings.first()?.let { SavedConfig(it.provider, it.model, it.baseUrl, it.hasKey) }
            _state.update { it.copy(saved = saved, editing = false, apiKey = "", busy = false) }
        }
    }

    /** Открыть мастер с сохранёнными провайдером и адресом; проверку нужно пройти заново. */
    fun edit() {
        val saved = _state.value.saved ?: return
        checkJob?.cancel()
        _state.update {
            it.copy(
                editing = true,
                provider = saved.provider,
                baseUrl = if (saved.provider == LlmProviderKind.OTHER) saved.baseUrl else "",
                apiKey = "",
            )
                .invalidated()
                .copy(model = saved.model)
        }
    }

    /** Мастер облачного провайдера, когда сохранена только модель на телефоне. */
    fun startWizard() = _state.update { it.copy(editing = true) }

    fun cancelEdit() {
        if (_state.value.saved == null && _state.value.onDevice == null) return
        checkJob?.cancel()
        _state.update { it.copy(editing = false, apiKey = "").invalidated() }
    }

    fun clearKey() {
        viewModelScope.launch {
            store.clearKey()
            _state.update { it.copy(saved = it.saved?.copy(hasKey = false)) }
        }
    }

    /** Забыть всё и вернуться к мастеру с OpenRouter. */
    fun reset() {
        checkJob?.cancel()
        viewModelScope.launch {
            store.clear()
            onDevice?.deactivate()
            _state.value = AiSettingsUiState(
                loaded = true,
                onDeviceAvailable = _state.value.onDeviceAvailable,
                onDeviceInOtherBuild = _state.value.onDeviceInOtherBuild,
            )
        }
    }

    private fun ConnectionCheck.toCheckState(provider: LlmProviderKind): CheckState = when (this) {
        is ConnectionCheck.Ok ->
            if (listed) CheckState.Passed(models, true, "Подключение работает: ${models.size} ${modelsWord(models.size)}")
            else CheckState.Passed(emptyList(), false, "Сервер отвечает, но не отдаёт список моделей — введите id модели вручную.")
        ConnectionCheck.NoModels ->
            if (provider == LlmProviderKind.OLLAMA) CheckState.Failed("Ollama отвечает, но моделей нет — скачайте модель на ПК: ollama pull llama3.2")
            else CheckState.Failed("Сервер ответил, но список моделей пуст.")
        ConnectionCheck.KeyMissing -> CheckState.Failed("Введите API-ключ.")
        is ConnectionCheck.InvalidUrl -> CheckState.Failed(reason)
        is ConnectionCheck.Unauthorized ->
            CheckState.Failed("Неверный API-ключ (401). Проверьте, что ключ скопирован полностью.".withDetail(detail))
        is ConnectionCheck.Blocked ->
            CheckState.Failed("Доступ запрещён (403): провайдер блокирует запросы из этой сети или региона. Включите VPN и проверьте снова.".withDetail(detail))
        is ConnectionCheck.Unreachable -> CheckState.Failed("Сервер недоступен: ${detail ?: "проверьте адрес и интернет"}.")
        is ConnectionCheck.Failed -> CheckState.Failed("Сервер ответил ошибкой $status.".withDetail(detail))
    }

    private fun String.withDetail(detail: String?) = if (detail.isNullOrBlank()) this else "$this Ответ сервера: «$detail»"

    private fun modelsWord(n: Int): String = when {
        n % 10 == 1 && n % 100 != 11 -> "модель"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "модели"
        else -> "моделей"
    }
}

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
import io.github.iampopovich.eculense.core.ai.NetworkProblem
import io.github.iampopovich.eculense.core.ai.UrlProblem
import io.github.iampopovich.eculense.core.ui.UiText
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

    /** [listed] = false: the custom server returned no list, the model is entered manually. */
    data class Passed(val models: List<ModelInfo>, val listed: Boolean, val message: UiText) : CheckState
    data class Failed(val message: UiText) : CheckState
}

internal data class SavedConfig(val provider: LlmProviderKind, val model: String, val baseUrl: String, val hasKey: Boolean)

/**
 * Connection wizard: provider → address + key → check (proves the key and loads the catalog) → model → save.
 * The «Модель» step opens only after a successful check; any edit of address/key/provider resets it.
 */
internal data class AiSettingsUiState(
    val loaded: Boolean = false,
    val saved: SavedConfig? = null,
    val editing: Boolean = true,
    val provider: LlmProviderKind = LlmProviderKind.DEFAULT,
    /** The address is entered only for «Другой (свой сервер)»; for presets Koog sets it. */
    val baseUrl: String = "",
    val apiKey: String = "",
    val urlError: UiText? = null,
    val check: CheckState = CheckState.Idle,
    val query: String = "",
    val model: String = "",
    val busy: Boolean = false,
    /** The «На устройстве» module is included: the provider list has an entry leading to its screen. */
    val onDeviceAvailable: Boolean = false,
    /** Cloud build: instead of the «На устройстве» entry, a dimmed line «доступно в сборке «Локально»». */
    val onDeviceInOtherBuild: Boolean = false,
    /** An on-device model is selected: chat goes through it (the cloud setting, if any, is inactive). */
    val onDevice: OnDeviceSummary? = null,
) {
    /** Empty key field = use the saved one (only for the same provider). */
    val canReuseSavedKey: Boolean get() = saved?.hasKey == true && saved.provider == provider
    /** Ollama has no key, so the field is hidden. */
    val showKeyField: Boolean get() = provider.usesKey
    /** Address field only for «Другой (свой сервер)». */
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
    /** On-device model (:core:ailocal), if the module is included. */
    private val onDevice: OnDeviceLlm? = null,
    onDeviceRoute: Boolean = false,
    /** Build flavor flag (BuildCapabilities): local models exist only in the «Локально» build. */
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

    /** «Перейти на облачного провайдера»: the local model stops being used, files remain. */
    fun useCloud() {
        viewModelScope.launch {
            onDevice?.deactivate()
            _state.update { it.copy(onDevice = null, editing = it.saved == null) }
        }
    }

    /** Reset the check and model selection: everything proven applied to the old address/key. */
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
        // The address is entered only for «Другого»; for presets Koog sets it.
        val url = if (s.provider != LlmProviderKind.OTHER) {
            null
        } else {
            when (val v = EndpointUrl.validate(s.baseUrl)) {
                is EndpointUrl.Result.Invalid -> {
                    _state.update { it.copy(urlError = v.problem.text(), check = CheckState.Idle) }
                    return
                }
                is EndpointUrl.Result.Valid -> v.url
            }
        }
        // A model chosen before the check (e.g. the saved one on «Изменить») stays if the server returns it.
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
                    urlError = (result as? ConnectionCheck.InvalidUrl)?.problem?.text(),
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
            // a cloud provider was saved, meaning it was chosen instead of the on-device model
            onDevice?.deactivate()
            val saved = store.settings.first()?.let { SavedConfig(it.provider, it.model, it.baseUrl, it.hasKey) }
            _state.update { it.copy(saved = saved, editing = false, apiKey = "", busy = false) }
        }
    }

    /** Open the wizard with the saved provider and address; the check must be passed again. */
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

    /** Cloud provider wizard when only an on-device model is saved. */
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

    /** Forget everything and return to the wizard with OpenRouter. */
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
            if (listed) CheckState.Passed(models, true, UiText.Plural(R.plurals.aisettings_check_ok, models.size))
            else CheckState.Passed(emptyList(), false, UiText.Res(R.string.aisettings_check_unlisted))
        ConnectionCheck.NoModels ->
            if (provider == LlmProviderKind.OLLAMA) CheckState.Failed(UiText.Res(R.string.aisettings_err_ollama_empty))
            else CheckState.Failed(UiText.Res(R.string.aisettings_err_empty_catalog))
        ConnectionCheck.KeyMissing -> CheckState.Failed(UiText.Res(R.string.aisettings_err_key_missing))
        is ConnectionCheck.InvalidUrl -> CheckState.Failed(problem.text())
        is ConnectionCheck.Unauthorized -> CheckState.Failed(UiText.Res(R.string.aisettings_err_401).withDetail(detail))
        is ConnectionCheck.Blocked -> CheckState.Failed(UiText.Res(R.string.aisettings_err_403).withDetail(detail))
        is ConnectionCheck.Unreachable -> CheckState.Failed(
            UiText.Res(R.string.aisettings_err_unreachable, listOf(problem.text(detail))),
        )
        is ConnectionCheck.Failed -> CheckState.Failed(UiText.Res(R.string.aisettings_err_status, listOf(status)).withDetail(detail))
    }

    /** The server's own reason is shown verbatim after our localized explanation. */
    private fun UiText.withDetail(detail: String?): UiText =
        if (detail.isNullOrBlank()) this else UiText.Res(R.string.aisettings_with_detail, listOf(this, UiText.Raw(detail)))
}

internal fun UrlProblem.text(): UiText = UiText.Res(
    when (this) {
        UrlProblem.EMPTY -> R.string.aisettings_url_empty
        UrlProblem.WHITESPACE -> R.string.aisettings_url_whitespace
        UrlProblem.MALFORMED -> R.string.aisettings_url_malformed
        UrlProblem.SCHEME -> R.string.aisettings_url_scheme
        UrlProblem.PORT -> R.string.aisettings_url_port
        UrlProblem.NO_HOST -> R.string.aisettings_url_no_host
        UrlProblem.QUERY -> R.string.aisettings_url_query
        UrlProblem.PUBLIC_HTTP -> R.string.aisettings_url_public_http
    },
)

/** Reason after «Server unreachable:»; an unknown failure shows its technical text or the generic hint. */
internal fun NetworkProblem.text(detail: String?): UiText = when (this) {
    NetworkProblem.NO_API -> UiText.Res(R.string.aisettings_net_no_api)
    NetworkProblem.NOT_API -> UiText.Res(R.string.aisettings_net_not_api)
    NetworkProblem.DNS -> UiText.Res(R.string.aisettings_net_dns)
    NetworkProblem.CLEARTEXT -> UiText.Res(R.string.aisettings_net_cleartext)
    NetworkProblem.CONNECT -> UiText.Res(R.string.aisettings_net_connect)
    NetworkProblem.TIMEOUT -> UiText.Res(R.string.aisettings_net_timeout)
    NetworkProblem.TLS -> UiText.Res(R.string.aisettings_net_tls)
    NetworkProblem.OTHER -> detail?.takeIf { it.isNotBlank() }?.let { UiText.Raw(it) } ?: UiText.Res(R.string.aisettings_err_unreachable_hint)
}

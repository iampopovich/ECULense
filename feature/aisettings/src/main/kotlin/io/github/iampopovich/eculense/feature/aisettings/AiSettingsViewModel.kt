package io.github.iampopovich.eculense.feature.aisettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.ai.LlmGateway
import io.github.iampopovich.eculense.core.ai.LlmProviderKind
import io.github.iampopovich.eculense.core.ai.LlmSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class AiSettingsForm(
    val provider: LlmProviderKind = LlmProviderKind.DEFAULT,
    val model: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val hasSavedKey: Boolean = false,
    val status: String? = null,
    val busy: Boolean = false,
)

internal class AiSettingsViewModel(private val store: LlmSettingsStore, private val gateway: LlmGateway) : ViewModel() {
    private val _form = MutableStateFlow(AiSettingsForm())
    val form: StateFlow<AiSettingsForm> = _form.asStateFlow()

    init {
        viewModelScope.launch {
            store.settings.first()?.let { s ->
                _form.value = AiSettingsForm(s.provider, s.model, s.baseUrl.orEmpty(), "", s.hasKey)
            }
        }
    }

    fun update(transform: (AiSettingsForm) -> AiSettingsForm) = _form.update(transform)

    /** Смена провайдера сбрасывает модель и адрес: у каждого свои id и URL. */
    fun selectProvider(provider: LlmProviderKind) = _form.update {
        if (it.provider == provider) it else it.copy(provider = provider, model = "", baseUrl = "", status = null)
    }

    fun saveAndTest() {
        if (_form.value.busy) return
        viewModelScope.launch {
            val f = _form.value
            store.save(f.provider, f.model.ifBlank { f.provider.modelHint }, f.baseUrl.ifBlank { f.provider.defaultBaseUrl }, f.apiKey)
            _form.update {
                it.copy(
                    model = f.model.ifBlank { f.provider.modelHint },
                    apiKey = "",
                    hasSavedKey = it.hasSavedKey || f.apiKey.isNotBlank(),
                    status = "Проверяю…",
                    busy = true,
                )
            }
            val result = gateway.ping()
            _form.update { it.copy(status = result.fold({ "Работает: $it" }, { "Ошибка: ${it.message}" }), busy = false) }
        }
    }

    fun clearKey() {
        viewModelScope.launch {
            store.clearKey()
            _form.update { it.copy(apiKey = "", hasSavedKey = false, status = "Ключ удалён") }
        }
    }
}

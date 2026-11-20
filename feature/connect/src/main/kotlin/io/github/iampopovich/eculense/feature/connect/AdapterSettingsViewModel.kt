package io.github.iampopovich.eculense.feature.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.data.AutomationPrefs
import io.github.iampopovich.eculense.core.data.AutomationSettings
import io.github.iampopovich.eculense.core.obd.AdaptiveTiming
import io.github.iampopovich.eculense.core.obd.AdapterPrefs
import io.github.iampopovich.eculense.core.obd.AdapterSettings
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.InitCommandProblem
import io.github.iampopovich.eculense.core.obd.InitCommands
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class AdapterSettingsUiState(
    val loaded: Boolean = false,
    val protocol: Char? = null,
    val timing: AdaptiveTiming = AdaptiveTiming.AUTO,
    val extraInit: String = "",
    val problems: List<InitCommandProblem> = emptyList(),
    /** More than InitCommands.MAX_COMMANDS lines. */
    val tooMany: Boolean = false,
    val saved: Boolean = false,
)

/** A11: manual protocol, extra init commands and adaptive timing; applied on the next connect. */
internal class AdapterSettingsViewModel(
    private val prefs: AdapterPrefs,
    controller: ConnectionController,
    /** C5 «Automation» group; null when the data layer is not wired (the group is hidden). */
    private val automationPrefs: AutomationPrefs? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(AdapterSettingsUiState())
    val state: StateFlow<AdapterSettingsUiState> = _state.asStateFlow()

    val automation: StateFlow<AutomationSettings?> = (automationPrefs?.settings ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setAutomation(transform: (AutomationSettings) -> AutomationSettings) {
        val p = automationPrefs ?: return
        viewModelScope.launch { p.update(transform) }
    }

    /** The protocol of the current connection, null when not connected. */
    val detectedProtocol: StateFlow<Char?> = controller.state
        .map { (it as? ConnectionState.Connected)?.info?.protocolNumber }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            val s = prefs.settings.first()
            _state.value = AdapterSettingsUiState(
                loaded = true,
                protocol = s.protocol,
                timing = s.adaptiveTiming,
                extraInit = s.extraInit.joinToString("\n"),
            )
        }
    }

    fun setProtocol(protocol: Char?) = _state.update { it.copy(protocol = protocol, saved = false) }

    fun setTiming(timing: AdaptiveTiming) = _state.update { it.copy(timing = timing, saved = false) }

    fun setExtraInit(text: String) = _state.update {
        val tooMany = InitCommands.lines(text).size > InitCommands.MAX_COMMANDS
        it.copy(extraInit = text, problems = InitCommands.problems(text), saved = false, tooMany = tooMany)
    }

    fun save() {
        val s = _state.value
        if (s.problems.isNotEmpty() || s.tooMany) return
        viewModelScope.launch {
            prefs.setSettings(AdapterSettings(s.protocol, InitCommands.valid(s.extraInit), s.timing))
            _state.update { it.copy(saved = true) }
        }
    }
}

package io.github.iampopovich.eculense.feature.dtc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.data.ModuleScanRepository
import io.github.iampopovich.eculense.core.data.StoredModule
import io.github.iampopovich.eculense.core.dtcdb.DtcInfo
import io.github.iampopovich.eculense.core.dtcdb.DtcSource
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.ModuleDtc
import io.github.iampopovich.eculense.core.obd.ModuleDtcRead
import io.github.iampopovich.eculense.core.obd.ModuleScanResult
import io.github.iampopovich.eculense.core.obd.ModuleScanner
import io.github.iampopovich.eculense.core.obd.ScanProgress
import io.github.iampopovich.eculense.core.obd.StandardPids
import io.github.iampopovich.eculense.core.obdtext.ObdText
import io.github.iampopovich.eculense.core.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One code of a module with its description (manufacturer codes only for the active car's make). */
internal data class ModuleDtcItem(val dtc: ModuleDtc, val info: DtcInfo)

/** A found module as a compact row. [emission] = an OBD emission ECU (its codes are cleared on the DTC screen). */
internal data class ModuleRow(
    val address: String,
    val kind: io.github.iampopovich.eculense.core.obd.ModuleKind,
    val emission: Boolean,
    val read: ModuleDtcRead,
    val items: List<ModuleDtcItem>,
) {
    val dtcCount: Int? get() = (read as? ModuleDtcRead.Codes)?.dtcs?.size
}

internal data class ModulesUiState(
    val connected: Boolean = false,
    /** CAN or K-Line of the current link (null = not connected). */
    val can: Boolean? = null,
    val speed: SpeedGate = SpeedGate.UNKNOWN,
    val speedSupported: Boolean = true,
    val scanning: Boolean = false,
    val progress: UiText? = null,
    val rows: List<ModuleRow> = emptyList(),
    val scannedAtMs: Long? = null,
    /** The rows come from the last stored scan of this car, not from a scan now. */
    val fromHistory: Boolean = false,
    val checked: Boolean = false,
    val limited: Boolean = false,
    val error: UiText? = null,
    val selected: Int? = null,
) {
    /** Read-only, but still only with the car standing (the safe-clear speed gate): the scan pauses live data. */
    val canScan: Boolean get() = connected && !scanning && (speed == SpeedGate.STOPPED || !speedSupported)
}

/**
 * R-D «Vehicle modules»: finds the car's modules with [ModuleScanner] (read-only, policy-checked, airbags never asked)
 * and shows their codes. Results of real links are stored per car ([ModuleScanRepository], gated for simulated links).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class VehicleModulesViewModel(
    private val controller: ConnectionController,
    private val descriptions: DtcDescriber,
    private val repository: ModuleScanRepository,
    private val now: () -> Long = System::currentTimeMillis,
    ticks: Flow<Unit> = untilStale(),
    private val scanner: ModuleScanner = ModuleScanner(),
) : ViewModel() {
    private val state = MutableStateFlow(ModulesUiState())
    private val lastSpeed = MutableStateFlow<Pair<Double, Long>?>(null)

    val uiState: StateFlow<ModulesUiState> = combine(state, controller.state, lastSpeed.flatMapLatest { if (it == null) flowOf(Unit) else ticks }) { s, c, _ ->
        val connected = c as? ConnectionState.Connected
        s.copy(
            connected = connected != null,
            can = connected?.info?.isCan,
            speed = DtcViewModel.gateOf(lastSpeed.value, now()),
            speedSupported = connected == null || connected.supportedPids.isEmpty() || DtcViewModel.SPEED in connected.supportedPids,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, state.value)

    init {
        viewModelScope.launch { runCatching { loadStored() } }
        viewModelScope.launch {
            controller.state.collect { s ->
                val polled = if (s is ConnectionState.Connected && DtcViewModel.SPEED in s.supportedPids) listOfNotNull(StandardPids.byCode(DtcViewModel.SPEED)) else emptyList()
                controller.setPolledPids(OWNER, polled)
            }
        }
        viewModelScope.launch {
            controller.readings.collect { r -> if (r.sensorId == DtcViewModel.SPEED_ID) lastSpeed.value = r.value to now() }
        }
    }

    override fun onCleared() {
        controller.setPolledPids(OWNER, emptyList())
    }

    internal fun onClearedForTest() = onCleared()

    fun scan() {
        if (!uiState.value.canScan) {
            if (uiState.value.speed == SpeedGate.MOVING) state.update { it.copy(error = UiText.Res(R.string.modules_stop_car)) }
            return
        }
        viewModelScope.launch {
            state.update { it.copy(scanning = true, error = null, selected = null, progress = UiText.Res(R.string.modules_discovering)) }
            try {
                val result = controller.withExclusiveSession { session -> scanner.scan(session) { p -> state.update { it.copy(progress = progressText(p)) } } }
                if (result == null) {
                    state.update { it.copy(error = UiText.Res(R.string.dtc_err_no_connection)) }
                } else {
                    show(result)
                    runCatching { repository.save(result) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(error = ObdText.error(e)) }
            } finally {
                state.update { it.copy(scanning = false, progress = null) }
            }
        }
    }

    fun open(index: Int) = state.update { if (index in it.rows.indices) it.copy(selected = index) else it }

    fun close() = state.update { it.copy(selected = null) }

    private suspend fun show(result: ModuleScanResult) {
        val rows = rowsOf(result.modules.map { StoredModule(it.module.address.label, it.module.kind, it.read, it.module.emission) })
        state.update { it.copy(rows = rows, checked = true, fromHistory = false, scannedAtMs = now(), limited = result.limitedByAdapter) }
    }

    private suspend fun loadStored() {
        val stored = repository.latest() ?: return
        val rows = rowsOf(stored.modules)
        state.update { if (it.checked) it else it.copy(rows = rows, fromHistory = true, scannedAtMs = stored.atMs) }
    }

    private suspend fun rowsOf(modules: List<StoredModule>): List<ModuleRow> {
        val codes = modules.flatMap { (it.read as? ModuleDtcRead.Codes)?.dtcs.orEmpty().map { d -> d.code } }.toSet()
        val infos = runCatching { descriptions.describe(codes) }.getOrDefault(emptyMap())
        return modules.map { m ->
            val items = (m.read as? ModuleDtcRead.Codes)?.dtcs.orEmpty().map { ModuleDtcItem(it, infos[it.code] ?: DtcInfo(it.code, null, DtcSource.UNKNOWN)) }
            ModuleRow(m.address, m.kind, m.emission, m.read, items)
        }
    }

    private fun progressText(p: ScanProgress): UiText = when (p) {
        ScanProgress.Discovering -> UiText.Res(R.string.modules_discovering)
        is ScanProgress.Probing -> UiText.Res(R.string.modules_probing, listOf(p.address.label, p.index, p.total))
        is ScanProgress.Reading -> UiText.Res(R.string.modules_reading, listOf(p.module.address.label))
    }

    internal companion object {
        const val OWNER = "modules"
    }
}

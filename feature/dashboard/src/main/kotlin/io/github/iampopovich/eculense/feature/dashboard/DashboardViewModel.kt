package io.github.iampopovich.eculense.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.analytics.LiveAlertKind
import io.github.iampopovich.eculense.core.analytics.SensorIds
import io.github.iampopovich.eculense.core.data.ActiveAlert
import io.github.iampopovich.eculense.core.data.AutomationPrefs
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.DashboardPrefs
import io.github.iampopovich.eculense.core.data.DashboardSelection
import io.github.iampopovich.eculense.core.data.EventMarker
import io.github.iampopovich.eculense.core.data.LiveAlerts
import io.github.iampopovich.eculense.core.data.MarkResult
import io.github.iampopovich.eculense.core.data.SessionRecorder
import io.github.iampopovich.eculense.core.data.UnitsRepository
import io.github.iampopovich.eculense.core.model.CarProfile
import kotlinx.coroutines.flow.first
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.DEFAULT_OWNER
import io.github.iampopovich.eculense.core.obd.Pid
import io.github.iampopovich.eculense.core.obd.PollTiers
import io.github.iampopovich.eculense.core.obd.StandardPids
import io.github.iampopovich.eculense.core.obd.isSessionAlive
import io.github.iampopovich.eculense.core.obdtext.ObdText
import io.github.iampopovich.eculense.core.ui.UiText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal data class DashboardUiState(
    val connected: Boolean = false,
    val tiles: List<TileState> = emptyList(),
    val recording: Boolean = false,
    /** Reconnecting / engine off: one line above the values (the last values stay on screen, dimmed). */
    val banner: UiText? = null,
    /** Short "adapter · protocol · voltage" line, only while connected. */
    val status: UiText? = null,
    val keepScreenOn: Boolean = true,
    val hudMirror: Boolean = false,
    val drivingTiles: Int = DashboardSelection.DEFAULT_DRIVING_TILES,
    /** C4: threshold alerts (full-width banner, the tile turns red). */
    val alerts: List<ActiveAlert> = emptyList(),
    /** C4: codes that appeared during this connection (banner opens the DTC screen). */
    val newCodes: List<String> = emptyList(),
    /** C8: «which car?» sheet before a recording starts. */
    val carChoice: List<CarProfile>? = null,
    /** B10: result of the last «Mark event» tap, shown once. */
    val markResult: MarkResult? = null,
)

/** Sensors whose tile turns into the error color for an alert kind. */
internal fun LiveAlertKind.sensorIds(): Set<String> = when (this) {
    LiveAlertKind.OVERHEAT -> setOf(SensorIds.COOLANT)
    LiveAlertKind.NOT_CHARGING, LiveAlertKind.OVERCHARGE -> setOf(SensorIds.MODULE_VOLTAGE, SensorIds.ELM_VOLTAGE)
}

/** The voltage is passed as a number: the resource formats it with the app locale (12.6 V in English, 12,6 V in Russian). */
internal fun ConnectionState.statusLine(liveVoltage: Double? = null): UiText? {
    if (this !is ConnectionState.Connected) return null
    val voltage = liveVoltage ?: info.batteryVoltage
    return if (voltage != null) {
        UiText.Res(R.string.dash_status_voltage, listOf(ObdText.endpointLabel(endpoint), info.protocolNumber, voltage))
    } else {
        UiText.Res(R.string.dash_status, listOf(ObdText.endpointLabel(endpoint), info.protocolNumber))
    }
}

/** Ticks for value ages and dimming (C3): once a second. */
internal fun everySecond(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(1_000)
    }
}

/**
 * Dashboard and driving mode (both screens use this ViewModel). Polls the user's PID selection for the active car
 * ([DashboardPrefs], default = [StandardPids.dashboardDefaults]); PID 03 is added when a fuel trim is shown (open loop
 * marker), MAF when the fuel rate has to be estimated.
 */
internal class DashboardViewModel(
    private val controller: ConnectionController,
    private val recorder: SessionRecorder,
    units: UnitsRepository,
    private val prefs: DashboardPrefs,
    private val now: () -> Long = System::currentTimeMillis,
    ticks: Flow<Unit> = everySecond(),
    private val alerts: LiveAlerts? = null,
    private val marker: EventMarker? = null,
    private val cars: CarRepository? = null,
    private val automation: AutomationPrefs? = null,
) : ViewModel() {
    private val buffer = LiveBuffer()
    private val carChoice = MutableStateFlow<List<CarProfile>?>(null)
    private val markResult = MutableStateFlow<MarkResult?>(null)
    private val extras = combine(
        alerts?.active ?: MutableStateFlow(emptyList()),
        alerts?.newCodes ?: MutableStateFlow(emptyList()),
        carChoice,
        markResult,
    ) { a, c, choice, mark -> Extras(a, c, choice, mark) }

    /** Bumped when the buffer changes (at most every [REDRAW_MS]) so the UI is not rebuilt for every reading. */
    private val version = MutableStateFlow(0L)
    private var lastRedraw = Long.MIN_VALUE

    // Reconnecting / engine off / connecting keep the last Connected state, so polling resumes with the same PIDs.
    private val lastConnected: StateFlow<ConnectionState> =
        controller.state.filter { it is ConnectionState.Connected || it is ConnectionState.Disconnected || it is ConnectionState.Failed }
            .stateIn(viewModelScope, SharingStarted.Eagerly, controller.state.value)

    private val selected: StateFlow<List<Pid>> = combine(lastConnected, prefs.selection, prefs.fuelEstimate) { state, saved, estimate ->
        if (state is ConnectionState.Connected) {
            DashboardSelection.resolve(saved, state.supportedPids, estimate).mapNotNull { StandardPids.byCode(it) }
        } else {
            emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val fuelEstimate: StateFlow<Boolean> = prefs.fuelEstimate.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val display = combine(prefs.keepScreenOn, prefs.hudMirror, prefs.drivingTiles) { a, b, c -> Triple(a, b, c) }

    private val facts = combine(controller.state, controller.disabledPids, controller.pollStats, recorder.activeSessionId) { s, d, p, r ->
        Facts(s, d, p?.cycleMsP50, r != null)
    }

    /** Values are stored in metric; km/h → mph, °C → °F etc. only at display time. */
    val uiState: StateFlow<DashboardUiState> =
        combine(
            combine(facts, selected, combine(version, ticks) { v, _ -> v }, units.units, display) { f, pids, _, u, d ->
                val t = now()
                DashboardUiState(
                    connected = f.state.isSessionAlive,
                    banner = ObdText.sessionState(f.state),
                    tiles = TileModel.build(pids, buffer, f.state, f.disabled, f.cycleMs, t, fuelEstimate.value) { v, unit -> u.display(v, unit) },
                    recording = f.recording,
                    status = f.state.statusLine(buffer.latest(PollTiers.ADAPTER_VOLTAGE_ID)?.value),
                    keepScreenOn = d.first,
                    hudMirror = d.second,
                    drivingTiles = d.third,
                )
            },
            extras,
        ) { base, x ->
            val alerting = x.alerts.flatMap { it.kind.sensorIds() }.toSet()
            base.copy(
                tiles = base.tiles.map { if (it.pid.sensorId in alerting) it.copy(alert = true) else it },
                alerts = x.alerts,
                newCodes = x.newCodes,
                carChoice = x.carChoice,
                markResult = x.mark,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    init {
        viewModelScope.launch { selected.collect { controller.setPolledPids(DEFAULT_OWNER, polledFor(it, lastConnected.value, fuelEstimate.value)) } }
        viewModelScope.launch {
            controller.readings.collect { r ->
                val t = now()
                buffer.accept(r, t)
                if (lastRedraw == Long.MIN_VALUE || t - lastRedraw >= REDRAW_MS) {
                    lastRedraw = t
                    version.value++
                }
            }
        }
        viewModelScope.launch {
            // A new connection starts with empty tiles: old values belong to another car or session.
            controller.state.filter { it is ConnectionState.Connecting }.collect { buffer.clear(); version.value++ }
        }
    }

    /**
     * [name] turns the "dd.MM HH:mm" stamp into the session name (the UI passes the localized template). C8: with more
     * than one car and «ask» on, the car sheet opens first ([startRecordingFor]).
     */
    fun toggleRecording(name: (stamp: String) -> String = { it }) {
        if (recorder.activeSessionId.value != null) {
            recorder.stop()
            return
        }
        pendingName = name
        viewModelScope.launch {
            val all = runCatching { cars?.cars() }.getOrNull().orEmpty()
            val ask = automation?.settings?.first()?.askCar ?: true
            if (all.size > 1 && ask) carChoice.value = all else startRecordingFor(null, dontAskAgain = false)
        }
    }

    private var pendingName: (String) -> String = { it }

    /** [carId] = null → the active car. */
    fun startRecordingFor(carId: Long?, dontAskAgain: Boolean) {
        carChoice.value = null
        if (dontAskAgain) viewModelScope.launch { automation?.update { it.copy(askCar = false) } }
        if (recorder.activeSessionId.value == null) {
            recorder.start(pendingName(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))), carId, auto = false)
        }
    }

    fun dismissCarChoice() {
        carChoice.value = null
    }

    /** B10: one-tap mark in the running recording. */
    fun markEvent() {
        val m = marker ?: return
        viewModelScope.launch { markResult.value = m.mark() }
    }

    fun markShown() {
        markResult.value = null
    }

    fun dismissNewCodes() {
        alerts?.dismissNewCodes()
    }

    fun setHudMirror(on: Boolean) {
        viewModelScope.launch { prefs.setHudMirror(on) }
    }

    fun setDrivingTiles(count: Int) {
        viewModelScope.launch { prefs.setDrivingTiles(count) }
    }

    private data class Facts(val state: ConnectionState, val disabled: Set<Int>, val cycleMs: Long?, val recording: Boolean)

    private data class Extras(val alerts: List<ActiveAlert>, val newCodes: List<String>, val carChoice: List<CarProfile>?, val mark: MarkResult?)

    internal companion object {
        const val REDRAW_MS = 250L

        /** What the poller needs for [shown] tiles: helpers for the open-loop marker and the fuel-rate estimate. */
        fun polledFor(shown: List<Pid>, state: ConnectionState, estimate: Boolean = true): List<Pid> {
            val supported = (state as? ConnectionState.Connected)?.supportedPids ?: return shown
            val extra = buildList {
                if (shown.any { it.code in StandardPids.FUEL_TRIMS } && 0x03 in supported) add(0x03)
                if (estimate && shown.any { it.code == DashboardSelection.FUEL_RATE }) {
                    if (DashboardSelection.MAF in supported) add(DashboardSelection.MAF)
                }
            }
            val fuelRateOnlyEstimated = DashboardSelection.FUEL_RATE !in supported
            return (shown.filterNot { it.code == DashboardSelection.FUEL_RATE && fuelRateOnlyEstimated } +
                extra.mapNotNull { StandardPids.byCode(it) }).distinctBy { it.code }
        }
    }
}

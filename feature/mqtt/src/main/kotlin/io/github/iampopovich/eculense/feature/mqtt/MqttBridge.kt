package io.github.iampopovich.eculense.feature.mqtt

import io.github.iampopovich.eculense.core.data.DashboardPrefs
import io.github.iampopovich.eculense.core.data.DashboardSelection
import io.github.iampopovich.eculense.core.data.ExtendedSignal
import io.github.iampopovich.eculense.core.model.AppLogger
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.mqtt.HaDiscovery
import io.github.iampopovich.eculense.core.mqtt.MqttConfig
import io.github.iampopovich.eculense.core.mqtt.MqttMessage
import io.github.iampopovich.eculense.core.mqtt.MqttPublisher
import io.github.iampopovich.eculense.core.mqtt.MqttState
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.Pid
import io.github.iampopovich.eculense.core.obd.PollTiers
import io.github.iampopovich.eculense.core.obd.StandardPids
import io.github.iampopovich.eculense.core.obd.isSessionAlive
import io.github.iampopovich.eculense.core.obd.isSimulated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.round

/** What the bridge is doing, for the settings screen. */
sealed interface BridgeStatus {
    data object Off : BridgeStatus

    /** On, but no real adapter is connected (nothing is published). */
    data object WaitingForAdapter : BridgeStatus

    /** On, but the link is a demo / replay / PC emulator: simulated links never publish. */
    data object SimulatedLink : BridgeStatus

    data class Active(val mqtt: MqttState) : BridgeStatus
}

/**
 * R-F: publishes the live values of the connected car to the user's own MQTT broker with Home Assistant discovery.
 * Runs only while MQTT is on AND a REAL adapter session is alive (Connected / Reconnecting / EngineOff), i.e. inside
 * the connection foreground service lifetime; simulated links publish nothing. The socket uses the device's default
 * network (never the adapter Wi-Fi binder, which only binds OBD sockets).
 *
 * Published: retained discovery config (one HA device per install + car), retained availability «online»/«offline»
 * (Last Will) and every [MqttSettings.periodSec] a state JSON with the chosen values in stored metric units. Never VIN,
 * GPS, adapter MAC/name or the car's nickname: the HA device name is make + model + year.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MqttBridge(
    private val controller: ConnectionController,
    private val settings: MqttSettingsSource,
    private val dashboard: DashboardPrefs,
    private val activeCar: Flow<CarProfile?>,
    private val dtcCount: (carId: Long) -> Flow<Int?>,
    private val extendedSignals: Flow<List<ExtendedSignal>>,
    private val appVersion: String,
    private val publisher: MqttPublisher,
    private val scope: CoroutineScope,
    private val logger: AppLogger = AppLogger.NONE,
    private val ticks: (periodMs: Long) -> Flow<Unit> = ::every,
) {
    private val _status = MutableStateFlow<BridgeStatus>(BridgeStatus.Off)
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    /** Supported PIDs of the last real Connected state (kept through Reconnecting / EngineOff). */
    private data class Link(val alive: Boolean, val simulated: Boolean, val supported: Set<Int>, val stateName: String)

    private val link: Flow<Link> = controller.state.runningFold(Link(false, false, emptySet(), STATE_DISCONNECTED)) { last, s ->
        val supported = (s as? ConnectionState.Connected)?.supportedPids ?: if (s.isSessionAlive) last.supported else emptySet()
        Link(s.isSessionAlive, s.isSimulated, supported, stateName(s))
    }.drop(1) // the seed; the StateFlow delivers the current state right away

    private data class Plan(val settings: MqttSettings, val car: CarProfile?)

    private var running = false

    fun start() {
        scope.launch {
            combine(settings.settings, link.map { it.alive to it.simulated }.distinctUntilChanged(), activeCar) { s, (alive, simulated), car ->
                when {
                    !s.enabled || s.host.isBlank() -> BridgeStatus.Off to null
                    !alive -> BridgeStatus.WaitingForAdapter to null
                    simulated -> BridgeStatus.SimulatedLink to null
                    else -> null to Plan(s, car)
                }
            }.distinctUntilChanged().collectLatest { (idle, plan) ->
                if (plan == null) {
                    shutDown()
                    _status.value = idle ?: BridgeStatus.Off
                } else {
                    try {
                        publish(plan)
                    } finally {
                        controller.setPolledPids(OWNER, emptyList())
                    }
                }
            }
        }
    }

    private suspend fun shutDown() {
        controller.setPolledPids(OWNER, emptyList())
        if (running) {
            running = false
            publisher.stop(lastPrefix?.let(HaDiscovery::offlineMessage))
            logger.info(TAG, "mqtt: stopped")
        }
    }

    @Volatile private var lastPrefix: String? = null

    internal data class Selection(val pids: List<Pid>, val extended: List<ExtendedSignal>)

    private suspend fun publish(plan: Plan) = coroutineScope {
        val s = plan.settings
        val install = settings.installId()
        val node = HaDiscovery.nodeId(install, plan.car?.id)
        val prefix = HaDiscovery.cleanPrefix(s.prefix)
        val user = s.username.trim().ifEmpty { null }
        val config = MqttConfig(
            host = s.host.trim(),
            port = s.port,
            tls = s.tls,
            clientId = "eculense$install",
            username = user,
            password = if (user != null) settings.password() else null,
            will = HaDiscovery.offlineMessage(prefix),
        )
        val deviceName = HaDiscovery.carName(plan.car?.brand, plan.car?.model, plan.car?.year)
        val latest = ConcurrentHashMap<String, Double>()
        val selection: Flow<Selection> = combine(
            link.map { it.supported }.distinctUntilChanged(),
            dashboard.selection,
            dashboard.extendedSelection,
            extendedSignals,
        ) { supported, saved, extIds, signals ->
            if (!s.sendDashboard) {
                Selection(emptyList(), emptyList())
            } else {
                val byId = signals.associateBy { it.sensorId }
                Selection(
                    DashboardSelection.resolve(saved, supported, estimate = false).mapNotNull { StandardPids.byCode(it) },
                    extIds.mapNotNull { byId[it] },
                )
            }
        }.distinctUntilChanged()
        val supportedNow = MutableStateFlow(emptySet<Int>())
        val stateName = MutableStateFlow(STATE_CONNECTED)
        fun discovery(sel: Selection) = HaDiscovery.configMessage(node, HaDiscovery.config(node, deviceName, appVersion, prefix, sensors(sel, s)))
        fun poll(sel: Selection) {
            val voltagePid = if (s.sendBattery && VOLTAGE_PID in supportedNow.value) listOfNotNull(StandardPids.byCode(VOLTAGE_PID)) else emptyList()
            controller.setPolledPids(OWNER, (sel.pids + voltagePid).distinctBy { it.code })
        }

        launch { link.collect { supportedNow.value = it.supported; stateName.value = it.stateName } }
        launch { controller.readings.collect { r -> latest[r.sensorId] = r.value } }
        supportedNow.value = link.first().supported
        var current = selection.first()
        poll(current)
        launch {
            selection.collect { sel ->
                if (sel == current) return@collect
                current = sel
                poll(sel)
                if (running) publisher.publish(discovery(sel)) // the device gains/loses entities
            }
        }
        val dtc = MutableStateFlow<Int?>(null)
        plan.car?.id?.let { id -> launch { dtcCount(id).collect { dtc.value = it } } }

        logger.info(TAG, "mqtt: publishing to the user's broker (tls=${s.tls}) every ${s.periodSec} s")
        publisher.start(config) { listOf(HaDiscovery.onlineMessage(prefix), discovery(current)) }
        running = true
        lastPrefix = prefix
        launch { publisher.state.collect { _status.value = BridgeStatus.Active(it) } }
        val stateTopic = HaDiscovery.stateTopic(prefix, node)
        ticks(s.periodSec * 1_000L).collect {
            val values = stateValues(current, s, latest, stateName.value, dtc.value)
            publisher.publish(MqttMessage(stateTopic, HaDiscovery.state(values)))
        }
    }

    internal companion object {
        const val OWNER = "mqtt"
        const val TAG = "EcuMqtt"
        const val BATTERY_KEY = "battery_voltage"
        const val CONNECTION_KEY = "connection"
        const val DTC_KEY = "dtc_count"
        const val VOLTAGE_PID = 0x42
        const val STATE_CONNECTED = "connected"
        const val STATE_DISCONNECTED = "disconnected"

        fun every(periodMs: Long): Flow<Unit> = flow {
            while (true) {
                delay(periodMs)
                emit(Unit)
            }
        }

        fun stateName(s: ConnectionState): String = when (s) {
            is ConnectionState.Connected -> STATE_CONNECTED
            is ConnectionState.Reconnecting -> "reconnecting"
            is ConnectionState.EngineOff -> "engine_off"
            else -> STATE_DISCONNECTED
        }

        /** HA entities (English names: HA-facing data like the OBDb names, not app UI text). */
        private fun sensors(sel: Selection, s: MqttSettings): List<HaDiscovery.Sensor> = buildList {
            sel.pids.forEach { add(HaDiscovery.Sensor(HaDiscovery.key(it.sensorId), it.name, it.unit.ifEmpty { null })) }
            sel.extended.forEach { add(HaDiscovery.Sensor(HaDiscovery.key(it.sensorId), it.name, it.unit.ifEmpty { null })) }
            if (s.sendBattery) add(HaDiscovery.Sensor(BATTERY_KEY, "Battery voltage", "V"))
            if (s.sendConnection) add(HaDiscovery.Sensor(CONNECTION_KEY, "Adapter connection", null, numeric = false))
            if (s.sendDtcCount) add(HaDiscovery.Sensor(DTC_KEY, "Trouble codes (last check)", null))
        }

        private fun r2(v: Double?): Double? = v?.let { round(it * 100) / 100 }

        fun stateValues(sel: Selection, s: MqttSettings, latest: Map<String, Double>, stateName: String, dtc: Int?): Map<String, Any?> = buildMap {
            sel.pids.forEach { put(HaDiscovery.key(it.sensorId), r2(latest[it.sensorId])) }
            sel.extended.forEach { put(HaDiscovery.key(it.sensorId), r2(latest[it.sensorId])) }
            if (s.sendBattery) put(BATTERY_KEY, r2(latest[PollTiers.ADAPTER_VOLTAGE_ID] ?: latest[StandardPids.byCode(VOLTAGE_PID)!!.sensorId]))
            if (s.sendConnection) put(CONNECTION_KEY, stateName)
            if (s.sendDtcCount) put(DTC_KEY, dtc)
        }
    }
}

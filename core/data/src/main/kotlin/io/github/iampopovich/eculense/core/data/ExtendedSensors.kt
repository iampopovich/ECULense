package io.github.iampopovich.eculense.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import io.github.iampopovich.eculense.core.model.AppLogger
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.FuelType
import io.github.iampopovich.eculense.core.model.SensorReading
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.ExtendedPollCommand
import io.github.iampopovich.eculense.core.obd.ExtendedPollGroup
import io.github.iampopovich.eculense.core.obd.isSimulated
import io.github.iampopovich.eculense.core.obdb.EvRole
import io.github.iampopovich.eculense.core.obdb.EvSignals
import io.github.iampopovich.eculense.core.obdb.ObdbCommandPlanner
import io.github.iampopovich.eculense.core.obdb.ObdbUnits
import io.github.iampopovich.eculense.core.obdb.SignalCommand
import io.github.iampopovich.eculense.core.obdb.SignalDecoder
import io.github.iampopovich.eculense.core.obdb.SimulatedSignalset
import io.github.iampopovich.eculense.core.obdb.StoredSignalset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** R-E: one extended (OBDb) sensor of the connected car; [sensorId] = «obdb:<OBDb signal id>». */
data class ExtendedSignal(
    val sensorId: String,
    val name: String,
    val unit: String,
    val path: String?,
    val commandId: String,
    val role: EvRole? = null,
)

/** Where the extended sensors of the current link stand. */
sealed interface ExtendedStatus {
    /** No connected adapter. */
    data object NotConnected : ExtendedStatus

    /** The active car has no make/model: no signalset can be matched. */
    data object NeedsProfile : ExtendedStatus

    /** No OBDb signalset is downloaded for this car (e.g. a K-Line Kia Sorento BL: OBDb has none). */
    data class NoSignalset(val repo: String?) : ExtendedStatus

    /**
     * A signalset matched. [yearUnknown] = the car profile has no model year: only commands without a year filter are
     * used and the screen says the data is not verified for the model year. [protocolMismatch] = every command is
     * for another bus (CAN signalset on a K-Line car). [refused]/[debugSkipped] = commands not read.
     */
    data class Ready(
        val repo: String,
        val year: Int?,
        val signals: List<ExtendedSignal>,
        val commands: Int,
        val refused: Int,
        val debugSkipped: Int,
        val ev: Boolean,
        val simulated: Boolean,
        val protocolMismatch: Boolean,
        internal val groups: List<ExtendedPollGroup> = emptyList(),
    ) : ExtendedStatus {
        val yearUnknown: Boolean get() = year == null
    }
}

/** The «Extended sensors (OBDb)» switch; off by default. */
interface ExtendedSensorsPrefs {
    val enabled: Flow<Boolean>
    suspend fun setEnabled(on: Boolean)
}

internal class DataStoreExtendedSensorsPrefs(private val store: DataStore<Preferences>) : ExtendedSensorsPrefs {
    override val enabled: Flow<Boolean> = store.data.map { it[ENABLED] ?: false }
    override suspend fun setEnabled(on: Boolean) {
        store.edit { it[ENABLED] = on }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
    }
}

/**
 * R-E: extended sensors of the active car from its downloaded OBDb signalset (matched by make/model like the Signalset
 * screen; simulated endpoints bring their own, debug only). Commands are filtered by the car's model year (`filter`,
 * debug years of `dbg`/`dbgfilter` skipped), planned into ELM steps ([ObdbCommandPlanner]) and, when the switch is on,
 * handed to the connection as the poll owner [OWNER]: they are read between J1979 cycles, under the safety policy, and
 * arrive in the normal readings flow as «obdb:<id>» (so recordings store them; analytics ignore unknown ids).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExtendedSensors(
    private val controller: ConnectionController,
    private val activeCar: Flow<CarProfile?>,
    private val load: suspend (make: String, model: String, year: Int?) -> StoredSignalset?,
    private val prefs: ExtendedSensorsPrefs,
    private val simulated: List<SimulatedSignalset>,
    private val scope: CoroutineScope,
    private val logger: AppLogger = AppLogger.NONE,
) {
    private data class Link(val kind: String, val simulated: Boolean, val protocol: Char)

    private val link: Flow<Link?> = controller.state.map { s ->
        (s as? ConnectionState.Connected)?.let { Link(it.endpoint.kind, s.isSimulated, it.info.protocolNumber) }
    }.distinctUntilChanged()

    val enabled: StateFlow<Boolean> = prefs.enabled.stateIn(scope, SharingStarted.Eagerly, false)

    val status: StateFlow<ExtendedStatus> = combine(link, activeCar) { l, car -> l to car }
        .distinctUntilChanged()
        .mapLatest { (l, car) ->
            try {
                resolve(l, car)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, "extended sensors: signalset failed: ${e::class.java.simpleName}", e)
                ExtendedStatus.NoSignalset(null)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, ExtendedStatus.NotConnected)

    /** The link is battery-electric (EV signalset or profile fuel «electric»): the EV card is shown. */
    val electric: StateFlow<Boolean> = status.map { (it as? ExtendedStatus.Ready)?.ev == true }.stateIn(scope, SharingStarted.Eagerly, false)

    fun start() {
        scope.launch {
            combine(status, enabled) { s, on -> if (on && s is ExtendedStatus.Ready) s.groups else emptyList() }
                .distinctUntilChanged()
                .collect { groups ->
                    logger.info(TAG, "extended poll: ${groups.size} group(s), ${groups.sumOf { it.commands.size }} command(s)")
                    controller.setExtendedPoll(OWNER, groups)
                }
        }
    }

    fun setEnabled(on: Boolean) {
        scope.launch { prefs.setEnabled(on) }
    }

    private suspend fun resolve(l: Link?, car: CarProfile?): ExtendedStatus {
        if (l == null) return ExtendedStatus.NotConnected
        if (l.simulated) {
            // A simulated link never uses the user's car profile; only a demo that brings its own signalset has one.
            val sim = simulated.firstOrNull { it.endpointKind == l.kind } ?: return ExtendedStatus.NoSignalset(null)
            return ready(sim.signalset(), sim.year, l, simulated = true, profileElectric = false)
        }
        val make = car?.brand?.takeIf { it.isNotBlank() }
        val model = car?.model?.takeIf { it.isNotBlank() }
        if (make == null || model == null) return ExtendedStatus.NeedsProfile
        val stored = load(make, model, car.year) ?: return ExtendedStatus.NoSignalset(null)
        return ready(stored, car.year, l, simulated = false, profileElectric = car.fuelType == FuelType.ELECTRIC)
    }

    private fun ready(stored: StoredSignalset, year: Int?, l: Link, simulated: Boolean, profileElectric: Boolean): ExtendedStatus.Ready {
        val all = stored.signalset.commands
        val byYear = all.filter { c -> if (year != null) c.appliesTo(year) else c.filter == null }
        val usable = byYear.filter { !it.isDebug(year) }
        val plan = ObdbCommandPlanner.plan(usable, l.protocol, stored.signalset.ecus)
        val roles = EvSignals.roles(usable, stored.signalset.ecus).entries.associate { (role, id) -> id to role }
        val planned = plan.groups.flatMap { it.commands }
        val signals = planned.flatMap { p ->
            p.source.signals.filter { !it.hidden && it.fmt != null }.map { s ->
                ExtendedSignal(SENSOR_PREFIX + s.id, s.name, ObdbUnits.symbol(s.fmt?.unit), s.path, p.command.id, roles[s.id])
            }
        }.distinctBy { it.sensorId }
        val groups = plan.groups.map { g ->
            ExtendedPollGroup(
                g.header,
                g.periodMs,
                g.commands.map { p -> ExtendedPollCommand(p.command, p.periodMs) { messages, at -> readings(p.source, messages, at) } },
                g.policy,
            )
        }
        return ExtendedStatus.Ready(
            repo = stored.repo,
            year = year,
            signals = signals,
            commands = planned.size,
            refused = plan.refused.size,
            debugSkipped = byYear.size - usable.size,
            ev = profileElectric || EvSignals.isEv(usable, stored.signalset.ecus),
            simulated = simulated,
            protocolMismatch = planned.isEmpty() && plan.refused.isNotEmpty() && plan.refused.all { it.reason == io.github.iampopovich.eculense.core.obdb.PlanRefusal.PROTOCOL_MISMATCH },
            groups = groups,
        )
    }

    private fun readings(command: SignalCommand, messages: List<ByteArray>, at: Long): List<SensorReading> =
        SignalDecoder.decodeAll(command, messages).filterKeys { !it.hidden }.map { (s, v) ->
            SensorReading(SENSOR_PREFIX + s.id, v.value, ObdbUnits.symbol(s.fmt?.unit), at)
        }

    companion object {
        const val OWNER = "extended"
        const val SENSOR_PREFIX = "obdb:"
        private const val TAG = "EcuObdb"

        fun isExtended(sensorId: String): Boolean = sensorId.startsWith(SENSOR_PREFIX)
    }
}

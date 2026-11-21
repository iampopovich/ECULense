package io.github.iampopovich.eculense.core.data

import io.github.iampopovich.eculense.core.analytics.EngineTraitDetector
import io.github.iampopovich.eculense.core.analytics.EngineTraits
import io.github.iampopovich.eculense.core.analytics.NormSource
import io.github.iampopovich.eculense.core.analytics.Trait
import io.github.iampopovich.eculense.core.model.AppLogger
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import io.github.iampopovich.eculense.core.obd.ObdSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Mode 01 single-byte PID read (read service only); null = not answered. */
internal suspend fun ObdSession.readPidByte(pid: Int): Int? =
    request("01%02X".format(pid))?.firstOrNull { it.size >= 3 && (it[0].toInt() and 0xFF) == 0x41 && (it[1].toInt() and 0xFF) == pid }
        ?.let { it[2].toInt() and 0xFF }

/** Traits the ECU reports at connect: supported PIDs (O2 type, boost PID, MAF) + PID 51 (fuel) + PID 1C (OBD standard). */
internal suspend fun ConnectionController.detectTraits(supported: Set<Int>, atMs: Long): EngineTraits {
    val base = EngineTraitDetector.fromSupportedPids(supported, atMs)
    val (fuel, std) = withSession { s ->
        val f = if (0x51 in supported) runCatching { s.readPidByte(0x51) }.getOrNull() else null
        val o = if (0x1C in supported) runCatching { s.readPidByte(0x1C) }.getOrNull() else null
        f to o
    } ?: (null to null)
    return base.copy(
        fuel = fuel?.let(EngineTraitDetector::fuelFromPid51)?.let { Trait(it, NormSource.ECU, atMs) },
        obdStandard = std?.takeIf { it > 0 }?.let { Trait(it, NormSource.ECU, atMs) },
    )
}

/**
 * R-H: stores what the ECU says about the engine for the active car, once per connection, and boost seen in live MAP
 * vs barometric readings. Simulated links (demo, replay) never write ([LiveWriteGate]).
 */
class EngineTraitsRecorder internal constructor(
    private val controller: ConnectionController,
    private val gate: LiveWriteGate,
    private val activeCarId: suspend () -> Long?,
    private val repository: CarNormsRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: AppLogger = AppLogger.NONE,
) {
    private var detectedFor: ConnectionState.Connected? = null
    private val map = ArrayList<Double>()
    private var baro: Double? = null
    private var boostSaved = false

    fun start() {
        scope.launch {
            controller.state.collect { s ->
                if (s is ConnectionState.Connected && s !== detectedFor) {
                    detectedFor = s
                    map.clear(); baro = null; boostSaved = false
                    record(s)
                } else if (s is ConnectionState.Disconnected) {
                    detectedFor = null
                }
            }
        }
        scope.launch {
            controller.readings.collect { r ->
                if (boostSaved || controller.state.value !is ConnectionState.Connected) return@collect
                when (r.sensorId) {
                    "01.0B" -> if (map.size < 2_000) map += r.value
                    "01.33" -> baro = r.value
                    else -> return@collect
                }
                if (EngineTraitDetector.forcedFromBoost(map, baro) == true && gate.allowed()) {
                    boostSaved = true
                    save(EngineTraits(forcedInduction = Trait(true, NormSource.ECU, clock())))
                }
            }
        }
    }

    private suspend fun record(s: ConnectionState.Connected) {
        if (!gate.allowed()) return
        try {
            save(controller.detectTraits(s.supportedPids, clock()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("EcuNorms", "trait detection failed: ${e::class.java.simpleName}", e)
        }
    }

    private suspend fun save(traits: EngineTraits) {
        val car = activeCarId() ?: return
        repository.saveTraits(car, traits)
    }
}

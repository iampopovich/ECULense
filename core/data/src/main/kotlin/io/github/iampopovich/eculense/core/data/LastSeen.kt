package io.github.iampopovich.eculense.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import io.github.iampopovich.eculense.core.analytics.SensorIds
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.isSimulated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * C9: what the home-screen widget shows while no adapter is connected, per car: the last battery voltage and the
 * last code check (also «no codes», which the DTC history does not store). Real links only: demo, replay and the PC
 * emulator never write here ([LiveWriteGate]).
 */
data class LastSeen(
    val voltage: Double? = null,
    val voltageAtMs: Long? = null,
    /** Codes found by the last successful check (stored + pending + permanent), 0 = none. */
    val dtcCount: Int? = null,
    val dtcCheckedAtMs: Long? = null,
)

interface LastSeenStore {
    fun observe(carId: Long): Flow<LastSeen>
    suspend fun saveVoltage(carId: Long, volts: Double, atMs: Long)
    suspend fun saveDtcCheck(carId: Long, count: Int, atMs: Long)
}

internal class DataStoreLastSeen(private val store: DataStore<Preferences>) : LastSeenStore {
    private fun volts(car: Long) = doublePreferencesKey("v_$car")
    private fun voltsAt(car: Long) = longPreferencesKey("v_at_$car")
    private fun dtc(car: Long) = intPreferencesKey("dtc_$car")
    private fun dtcAt(car: Long) = longPreferencesKey("dtc_at_$car")

    override fun observe(carId: Long): Flow<LastSeen> = store.data.map { p ->
        LastSeen(p[volts(carId)], p[voltsAt(carId)], p[dtc(carId)], p[dtcAt(carId)])
    }

    override suspend fun saveVoltage(carId: Long, volts: Double, atMs: Long) {
        store.edit { it[volts(carId)] = volts; it[voltsAt(carId)] = atMs }
    }

    override suspend fun saveDtcCheck(carId: Long, count: Int, atMs: Long) {
        store.edit { it[dtc(carId)] = count; it[dtcAt(carId)] = atMs }
    }
}

/**
 * Keeps the last battery voltage of the active car: module voltage (01.42) when the car reports it, otherwise the
 * adapter's ATRV. Written at most once per [everyMs] so a long drive does not hammer the DataStore.
 */
internal class LastSeenRecorder(
    private val controller: ConnectionController,
    private val store: LastSeenStore,
    private val activeCarId: suspend () -> Long?,
    private val liveWrites: LiveWriteGate,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val everyMs: Long = 60_000L,
) {
    private var lastWriteMs = Long.MIN_VALUE

    fun start() {
        scope.launch {
            controller.readings.collect { r ->
                if (r.sensorId != SensorIds.MODULE_VOLTAGE && r.sensorId != SensorIds.ELM_VOLTAGE) return@collect
                if (!liveWrites.allowed() || controller.state.value.isSimulated) return@collect
                val now = clock()
                if (lastWriteMs != Long.MIN_VALUE && now - lastWriteMs < everyMs) return@collect
                val car = activeCarId() ?: return@collect
                lastWriteMs = now
                store.saveVoltage(car, r.value, now)
            }
        }
    }
}

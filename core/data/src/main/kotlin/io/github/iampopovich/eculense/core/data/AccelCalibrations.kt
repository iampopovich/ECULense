package io.github.iampopovich.eculense.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import io.github.iampopovich.eculense.core.analytics.AccelCalibration
import io.github.iampopovich.eculense.core.analytics.AccelCalibrationRules
import io.github.iampopovich.eculense.core.analytics.DriverThreshold
import io.github.iampopovich.eculense.core.analytics.DriverThresholds
import io.github.iampopovich.eculense.core.analytics.SpeedSample
import io.github.iampopovich.eculense.core.analytics.StyleThresholds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale

/** Whether this phone can log acceleration for the E5-lite calibration (from :core:sensors; null = module absent). */
enum class AccelSupport {
    LINEAR_ACCELERATION,
    ACCELEROMETER_MINUS_GRAVITY,
    /** Neither TYPE_LINEAR_ACCELERATION nor TYPE_ACCELEROMETER. */
    NO_ACCELEROMETER,
    /** No TYPE_GRAVITY: the horizontal plane can't be found. */
    NO_GRAVITY,
    ;

    val supported: Boolean get() = this == LINEAR_ACCELERATION || this == ACCELEROMETER_MINUS_GRAVITY
}

interface PhoneSensors {
    val accelSupport: AccelSupport
}

/**
 * Per-car harsh-event threshold override for D6 (m/s²), set from the calibration card. Absent = the sourced default
 * [StyleThresholds.HARSH_ACCEL_MS2] (Geotab 0.3 g).
 */
interface StylePrefs {
    fun harshMs2(carId: Long): Flow<Double?>
    suspend fun setHarshMs2(carId: Long, ms2: Double?)
}

class InMemoryStylePrefs : StylePrefs {
    private val map = MutableStateFlow<Map<Long, Double>>(emptyMap())
    override fun harshMs2(carId: Long): Flow<Double?> = map.map { it[carId] }
    override suspend fun setHarshMs2(carId: Long, ms2: Double?) = map.update { if (ms2 == null) it - carId else it + (carId to ms2) }
}

internal class DataStoreStylePrefs(private val store: DataStore<Preferences>) : StylePrefs {
    override fun harshMs2(carId: Long): Flow<Double?> = store.data.map { it[key(carId)] }
    override suspend fun setHarshMs2(carId: Long, ms2: Double?) {
        store.edit { if (ms2 == null) it.remove(key(carId)) else it[key(carId)] = ms2 }
    }

    private fun key(carId: Long) = doublePreferencesKey("harsh_ms2_$carId")
}

/** Everything the calibration card needs for one recording. */
data class CalibrationReport(val calibration: AccelCalibration, val samples: Int, val durationMs: Long, val source: AccelSource)

/**
 * E5-lite read side: calibration of one recording's accelerometer file against its OBD speed, the per-driver μ + 2σ
 * over the car's recordings and the CSV export. The raw file never leaves the phone except through the user's share.
 */
class AccelCalibrations(
    private val files: AccelFiles,
    /** Speed samples (01.0D) of a recording in [fromMs, toMs]. */
    private val speed: suspend (sessionId: Long, fromMs: Long, toMs: Long) -> List<SpeedSample>,
    /** Recording ids of the car's trips, newest first. */
    private val carSessions: suspend (carId: Long) -> List<Long>,
) {
    fun exists(sessionId: Long): Boolean = files.exists(sessionId)

    suspend fun report(sessionId: Long, fromMs: Long = Long.MIN_VALUE, toMs: Long = Long.MAX_VALUE): CalibrationReport? {
        val rec = files.read(sessionId) ?: return null
        val linear = rec.linear.filter { it.tMs in fromMs..toMs }.ifEmpty { rec.linear }
        val c = AccelCalibrationRules.calibrate(linear, rec.gravity, speed(sessionId, fromMs, toMs))
        val duration = if (linear.size > 1) linear.last().tMs - linear.first().tMs else 0L
        return CalibrationReport(c, linear.size, duration, rec.source)
    }

    suspend fun driverThreshold(carId: Long): DriverThreshold? =
        DriverThresholds.of(carSessions(carId).take(MAX_DRIVER_SESSIONS).map { speed(it, Long.MIN_VALUE, Long.MAX_VALUE) })

    /** CSV for laptop analysis: t_ms, a_long, a_lat, obd_speed, obd_windowed_derivative, h1, h2 (10 Hz rows). */
    suspend fun writeCsv(sessionId: Long, target: File): Int {
        val rec = files.read(sessionId) ?: return 0
        val rows = AccelCalibrationRules.rows(rec.linear, rec.gravity, speed(sessionId, Long.MIN_VALUE, Long.MAX_VALUE))
        fun f(v: Double?, digits: Int = 3) = v?.let { String.format(Locale.ROOT, "%.${digits}f", it) }.orEmpty()
        target.parentFile?.mkdirs()
        target.bufferedWriter().use { w ->
            w.write(CSV_HEADER)
            w.newLine()
            rows.forEach { r ->
                w.write("${r.tMs},${f(r.aLongMs2)},${f(r.aLatMs2)},${f(r.obdKmh, 1)},${f(r.obdDerivativeMs2)},${f(r.h1)},${f(r.h2)}")
                w.newLine()
            }
        }
        return rows.size
    }

    /** Summary numbers for the AI (English, no raw samples), null without a file. */
    suspend fun english(sessionId: Long, fromMs: Long, toMs: Long): String? = report(sessionId, fromMs, toMs)?.let { AccelCalibrationRules.english(it.calibration) }

    companion object {
        const val CSV_HEADER = "t_ms,a_long,a_lat,obd_speed,obd_windowed_derivative,h1,h2"
        /** Per-driver threshold reads at most this many latest recordings (keeps the card fast on big histories). */
        const val MAX_DRIVER_SESSIONS = 50

        suspend fun harshOrDefault(prefs: StylePrefs?, carId: Long?): Double =
            carId?.let { prefs?.harshMs2(it)?.first() } ?: StyleThresholds.HARSH_ACCEL_MS2
    }
}

package io.github.iampopovich.eculense.core.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import io.github.iampopovich.eculense.core.data.AccelFiles
import io.github.iampopovich.eculense.core.data.AccelSource
import io.github.iampopovich.eculense.core.data.AccelSupport
import io.github.iampopovich.eculense.core.data.AutomationPrefs
import io.github.iampopovich.eculense.core.data.PhoneSensors
import io.github.iampopovich.eculense.core.data.SessionRecorder
import io.github.iampopovich.eculense.core.data.SessionRepository
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.isSimulated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** SensorEvent.timestamp → the recorder's wall clock (readings use System.currentTimeMillis). */
object SensorClock {
    /**
     * SensorEvent.timestamp uses the SystemClock.elapsedRealtimeNanos() time base (SensorEvent docs). The event
     * happened (nowElapsed − event) ago, so on the wall clock it is now − that age. Computed per event, so a wall-clock
     * change during a trip shifts later samples exactly like it shifts the OBD readings.
     */
    fun toWallMs(eventNanos: Long, nowWallMs: Long, nowElapsedNanos: Long): Long = nowWallMs - (nowElapsedNanos - eventNanos) / 1_000_000
}

/** Pure decisions of the capture, unit-tested. */
object AccelCaptureRules {
    fun support(hasLinear: Boolean, hasGravity: Boolean, hasAccelerometer: Boolean): AccelSupport = when {
        !hasLinear && !hasAccelerometer -> AccelSupport.NO_ACCELEROMETER
        !hasGravity -> AccelSupport.NO_GRAVITY
        hasLinear -> AccelSupport.LINEAR_ACCELERATION
        else -> AccelSupport.ACCELEROMETER_MINUS_GRAVITY
    }

    /**
     * Session to capture, or null. Only while a recording runs and the setting is on; a demo/replay link is captured
     * only when the user started that recording by hand (an automatic recording never runs on a simulated link anyway).
     */
    fun target(sessionId: Long?, auto: Boolean, enabled: Boolean, simulated: Boolean, support: AccelSupport): Long? =
        sessionId?.takeIf { enabled && support.supported && !(simulated && auto) }

    /** Gravity is stored once per second. */
    const val GRAVITY_EVERY_MS = 1_000L
    /** 50 Hz: SensorManager.registerListener samplingPeriodUs. Below 200 Hz, so no HIGH_SAMPLING_RATE_SENSORS. */
    const val LINEAR_PERIOD_US = 20_000
    /** Gravity hint: 1 Hz when only stored; 5 Hz when it is also subtracted from the raw accelerometer. */
    const val GRAVITY_PERIOD_US = 1_000_000
    const val GRAVITY_FALLBACK_PERIOD_US = 200_000
    /** Safety cap on the wake lock; a recording longer than this keeps logging only while the CPU is awake. */
    const val WAKE_LOCK_MAX_MS = 12 * 60 * 60_000L
}

internal class AndroidPhoneSensors(context: Context) : PhoneSensors {
    private val sm = context.getSystemService(SensorManager::class.java)
    override val accelSupport: AccelSupport by lazy {
        if (sm == null) {
            AccelSupport.NO_ACCELEROMETER
        } else {
            AccelCaptureRules.support(
                sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null,
                sm.getDefaultSensor(Sensor.TYPE_GRAVITY) != null,
                sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
            )
        }
    }
}

/**
 * E5-lite capture: while a recording runs and «record phone accelerometer» is on, logs TYPE_LINEAR_ACCELERATION at
 * 50 Hz (or ACCELEROMETER − GRAVITY) and TYPE_GRAVITY once per second into `files/accel/<sessionId>.bin`.
 *
 * Screen off: since Android 9, continuous sensors (accelerometer) stop delivering to apps in the background, but an
 * app with a foreground service is not «in the background» for this rule
 * (developer.android.com/about/versions/pie/android-9.0-changes-all#bg-sensor-access). A recording runs with the
 * connectedDevice FGS of :core:service. Non-wake-up sensors also stop when the CPU suspends with the screen off, so a
 * partial wake lock (normal WAKE_LOCK permission, already in the app via WorkManager) is held only while capturing.
 * If the link drops during an automatic recording the FGS ends and sensor delivery may pause — a gap in the file,
 * same as the OBD readings.
 */
internal class AccelCapture(
    private val context: Context,
    private val recorder: SessionRecorder,
    private val prefs: AutomationPrefs,
    private val controller: ConnectionController,
    private val files: AccelFiles,
    private val sessions: SessionRepository,
    private val sensors: PhoneSensors,
    private val scope: CoroutineScope,
) {
    private var running: Running? = null

    fun start() {
        scope.launch {
            // Files of sessions deleted another way (whole car, restore of an older backup) are removed once per start.
            runCatching {
                val ids = sessions.observeSessions().first().map { it.id }.toSet()
                val swept = files.sweep(ids, keep = recorder.activeSessionId.value)
                if (swept > 0) Log.i(TAG, "swept $swept orphan files")
            }
            combine(recorder.activeSessionId, recorder.activeIsAuto, prefs.settings.map { it.recordAccel }) { id, auto, on ->
                AccelCaptureRules.target(id, auto, on, controller.state.value.isSimulated, sensors.accelSupport)
            }.distinctUntilChanged().collect { id ->
                running?.stop()
                running = id?.let { runCatching { Running(it) }.onFailure { e -> Log.w(TAG, "start failed", e) }.getOrNull() }
            }
        }
    }

    private inner class Running(private val sessionId: Long) : SensorEventListener {
        private val sm = context.getSystemService(SensorManager::class.java) ?: error("no SensorManager")
        private val support = sensors.accelSupport
        private val thread = HandlerThread("accel-capture").apply { start() }
        private val handler = Handler(thread.looper)
        private val startMs = System.currentTimeMillis()
        private val writer = files.writer(
            sessionId,
            if (support == AccelSupport.LINEAR_ACCELERATION) AccelSource.LINEAR_ACCELERATION else AccelSource.ACCELEROMETER_MINUS_GRAVITY,
            startMs - 1_000, // events can carry timestamps slightly before registration
        )
        private val gravity = FloatArray(3)
        private var hasGravity = false
        private var lastGravityMs = Long.MIN_VALUE
        private val wakeLock = context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eculense:accel")
            ?.apply { setReferenceCounted(false); acquire(AccelCaptureRules.WAKE_LOCK_MAX_MS) }

        init {
            val fallback = support != AccelSupport.LINEAR_ACCELERATION
            sm.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
                sm.registerListener(this, it, if (fallback) AccelCaptureRules.GRAVITY_FALLBACK_PERIOD_US else AccelCaptureRules.GRAVITY_PERIOD_US, handler)
            }
            val main = sm.getDefaultSensor(if (fallback) Sensor.TYPE_ACCELEROMETER else Sensor.TYPE_LINEAR_ACCELERATION)
            if (main != null) sm.registerListener(this, main, AccelCaptureRules.LINEAR_PERIOD_US, handler)
            Log.i(TAG, "started session=$sessionId source=$support")
        }

        override fun onSensorChanged(e: SensorEvent) {
            val t = SensorClock.toWallMs(e.timestamp, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
            val v = e.values
            runCatching {
                when (e.sensor.type) {
                    Sensor.TYPE_GRAVITY -> {
                        gravity[0] = v[0]; gravity[1] = v[1]; gravity[2] = v[2]
                        hasGravity = true
                        if (t - lastGravityMs >= AccelCaptureRules.GRAVITY_EVERY_MS) {
                            writer.gravity(t, v[0], v[1], v[2])
                            lastGravityMs = t
                        }
                    }
                    Sensor.TYPE_LINEAR_ACCELERATION -> writer.linear(t, v[0], v[1], v[2])
                    Sensor.TYPE_ACCELEROMETER -> if (hasGravity) writer.linear(t, v[0] - gravity[0], v[1] - gravity[1], v[2] - gravity[2])
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        fun stop() {
            sm.unregisterListener(this)
            handler.post {
                runCatching { writer.close() }
                val bytes = files.file(sessionId).length()
                val seconds = (System.currentTimeMillis() - startMs) / 1000.0
                Log.i(TAG, "stopped session=$sessionId samples=${writer.samples} bytes=$bytes seconds=$seconds")
                thread.quitSafely()
            }
            runCatching { wakeLock?.release() }
        }
    }

    private companion object {
        const val TAG = "EcuAccel"
    }
}

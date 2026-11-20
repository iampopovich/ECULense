package io.github.iampopovich.eculense.core.analytics

/**
 * B10 engine stall: rpm drops to 0 while the link and the ECU stay alive (the 0 is an answer, not a timeout), the
 * engine was running within the last [Thresholds.STALL_LOOKBACK_MS] (rpm above [Thresholds.RUNNING_RPM] or speed
 * above 0) and the port voltage, if known, still shows ignition on. One event per stall; re-armed once the engine
 * runs again. A switched-off engine looks different: the ECU stops answering (EngineOff), so it is never a stall.
 */
class StallDetector {
    private var lastRunningAt: Long? = null
    private var voltage: Double? = null
    private var stalled = false

    /** Returns true exactly when a stall is detected at this reading. */
    fun onReading(sensorId: String, value: Double, atMs: Long): Boolean {
        when (sensorId) {
            SensorIds.ELM_VOLTAGE, SensorIds.MODULE_VOLTAGE -> voltage = value
            SensorIds.SPEED -> if (value > 0) lastRunningAt = atMs
            SensorIds.RPM -> {
                if (value > Thresholds.RUNNING_RPM) {
                    lastRunningAt = atMs
                    stalled = false
                    return false
                }
                if (value > 0.0 || stalled) return false
                val recent = lastRunningAt?.let { atMs - it <= Thresholds.STALL_LOOKBACK_MS } == true
                val ignition = voltage?.let { it >= Thresholds.STALL_IGNITION_MIN_V } ?: true
                if (recent && ignition) {
                    stalled = true
                    return true
                }
            }
        }
        return false
    }

    /** The link went down or the engine-off state began: whatever follows is not a stall of this run. */
    fun reset() {
        lastRunningAt = null
        stalled = false
    }
}

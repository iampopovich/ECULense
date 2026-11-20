package io.github.iampopovich.eculense.core.demo

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A "car in motion" model for demo mode: everything is a pure function of time since connection,
 * with no Random, so tests are deterministic while on-screen values still look "alive".
 *
 * 70 s trip cycle: idle → acceleration → cruise → braking → idle.
 * Cruise speed varies from cycle to cycle. The engine warms up (coolant ~18 → ~90 °C);
 * while it's cold, idle RPM is higher.
 */
internal class DemoEngine(
    private val ambientC: Double = 18.0,
    private val startFuelPct: Double = 62.0,
) {
    private enum class Phase { IDLE, ACCEL, CRUISE, BRAKE }

    /** Values in StandardPids units (key is the service 01 PID number). */
    fun values(elapsedMs: Long, clearedAtMs: Long? = null): Map<Int, Double> {
        val t = max(0L, elapsedMs) / 1000.0
        val cycle = (t / CYCLE_S).toInt()
        val tc = t - cycle * CYCLE_S
        val target = CRUISE_TARGETS[cycle % CRUISE_TARGETS.size]

        val (phase, speed) = when {
            tc < 10 -> Phase.IDLE to 0.0
            tc < 25 -> Phase.ACCEL to target * smoothstep((tc - 10) / 15)
            tc < 45 -> Phase.CRUISE to target + 2.0 * noise(t, 1)
            tc < 58 -> Phase.BRAKE to target * (1 - smoothstep((tc - 45) / 13))
            else -> Phase.IDLE to 0.0
        }

        val coolant = ambientC + (90 - ambientC) * (1 - exp(-t / 150)) + 0.3 * noise(t, 2)
        val oil = ambientC + (95 - ambientC) * (1 - exp(-t / 300)) + 0.3 * noise(t, 3)
        val cold = ((90 - coolant) / (90 - ambientC)).coerceIn(0.0, 1.0)
        val idleRpm = 800 + 25 * noise(t, 4) + 250 * cold

        val rpm = if (speed < 3) idleRpm else max(idleRpm, speed * gearRatio(speed) + 30 * noise(t, 5))
        val n = noise(t, 6)
        val (throttle, load, map, timing) = when (phase) {
            Phase.IDLE -> listOf(13 + 0.5 * n, 20 + 2 * n + 8 * cold, 31 + 2 * n, 10 + 2 * n)
            Phase.ACCEL -> listOf(40 + 5 * n, 70 + 5 * n, 85 + 3 * n, 20 + 3 * n)
            Phase.CRUISE -> listOf(22 + 2 * n, 38 + 3 * n, 52 + 3 * n, 32 + 2 * n)
            Phase.BRAKE -> listOf(12.5, 10 + n, 22 + n, 25 + n)
        }
        val maf = 0.0002 * rpm * load
        val fuelCut = phase == Phase.BRAKE && rpm > 1100
        val fuelRate = if (fuelCut) 0.2 else maf * 0.331
        val stft = 2.5 * noise(t, 7) + if (phase == Phase.ACCEL) -2.0 else 0.0
        val distance = t * AVERAGE_SPEED_KMH / 3600
        val distanceSinceClear = if (clearedAtMs == null) 1250 + distance
        else (elapsedMs - clearedAtMs).coerceAtLeast(0) / 1000.0 * AVERAGE_SPEED_KMH / 3600

        return mapOf(
            0x04 to load,
            0x05 to coolant,
            0x06 to stft,
            0x07 to 6.3,
            0x0B to map,
            0x0C to rpm,
            0x0D to speed,
            0x0E to timing,
            0x0F to ambientC + 6 + 8 * (1 - exp(-t / 400)) - speed * 0.05,
            0x10 to maf,
            0x11 to throttle,
            0x1F to min(t, 65_535.0),
            0x21 to if (clearedAtMs == null) 37 + distance else 0.0,
            0x2F to max(5.0, startFuelPct - t * 0.004),
            0x31 to distanceSinceClear,
            0x33 to 99 + 0.3 * noise(t, 8),
            0x42 to voltage(elapsedMs),
            0x46 to ambientC,
            0x5C to oil,
            0x5E to fuelRate,
            // Open loop (1) while cold, closed loop (2) once warm; open loop (4) under fuel cut.
            0x03 to when { coolant < 40 -> 1.0; fuelCut -> 4.0; else -> 2.0 },
            0x08 to stft + 0.8 * noise(t, 10),
            0x09 to 4.7,
            0x14 to 0.45 + 0.4 * sin(t * 2.3),
            0x15 to 0.68 + 0.02 * noise(t, 11),
            0x30 to 12.0,
            0x3C to ambientC + 420 * (1 - exp(-t / 200)),
            0x43 to load * 0.9,
            0x44 to if (fuelCut) 1.99 else 1.0 + 0.01 * noise(t, 12),
            0x45 to max(0.0, throttle - 11),
        )
    }

    fun voltage(elapsedMs: Long): Double = 14.25 + 0.1 * noise(elapsedMs / 1000.0, 9)

    private fun gearRatio(speed: Double): Double = when {
        speed < 18 -> 110.0
        speed < 35 -> 60.0
        speed < 55 -> 42.0
        speed < 72 -> 32.0
        else -> 27.0
    }

    private fun smoothstep(x: Double): Double {
        val c = x.coerceIn(0.0, 1.0)
        return c * c * (3 - 2 * c)
    }

    /** Smooth deterministic "noise" in [-1, 1]; k decorrelates sensors so they don't jitter in sync. */
    private fun noise(t: Double, k: Int): Double =
        (sin(t * 1.3 + k) + 0.6 * sin(t * 2.7 + 2 * k + PI / 3) + 0.4 * sin(t * 7.1 + 3 * k)) / 2.0

    companion object {
        const val CYCLE_S = 70.0
        private val CRUISE_TARGETS = listOf(60.0, 85.0, 70.0, 95.0)
        private const val AVERAGE_SPEED_KMH = 45.0
        const val VIN = "KNDJC733545123456"
    }
}

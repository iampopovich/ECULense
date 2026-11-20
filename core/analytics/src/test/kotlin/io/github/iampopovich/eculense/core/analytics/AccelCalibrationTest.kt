package io.github.iampopovich.eculense.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

class AccelCalibrationTest {
    private val g = StyleThresholds.G

    /** Rotation car frame → device frame for a phone held at arbitrary yaw/pitch/roll (rigid mount). */
    private class Mount(yawDeg: Double, pitchDeg: Double, rollDeg: Double) {
        private val m: Array<DoubleArray>

        init {
            val (y, p, r) = Triple(yawDeg * PI / 180, pitchDeg * PI / 180, rollDeg * PI / 180)
            val rz = arrayOf(doubleArrayOf(cos(y), -sin(y), 0.0), doubleArrayOf(sin(y), cos(y), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
            val ry = arrayOf(doubleArrayOf(cos(p), 0.0, sin(p)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(p), 0.0, cos(p)))
            val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(r), -sin(r)), doubleArrayOf(0.0, sin(r), cos(r)))
            m = mul(mul(rz, ry), rx)
        }

        private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) =
            Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> a[i][k] * b[k][j] } } }

        /** Device-frame coordinates of a car-frame vector (transpose of the device→car rotation). */
        fun toDevice(x: Double, y: Double, z: Double) = Triple(
            m[0][0] * x + m[1][0] * y + m[2][0] * z,
            m[0][1] * x + m[1][1] * y + m[2][1] * z,
            m[0][2] * x + m[1][2] * y + m[2][2] * z,
        )
    }

    /** Car-frame longitudinal acceleration (m/s²) over time, piecewise: segments of (durationMs, accel). */
    private class Profile(val segments: List<Pair<Long, Double>>) {
        val totalMs = segments.sumOf { it.first }
        fun accel(t: Long): Double {
            var start = 0L
            for ((d, a) in segments) {
                if (t < start + d) return a
                start += d
            }
            return 0.0
        }
    }

    /** Integrates the profile at 1 ms into speed (km/h) starting from [v0]. */
    private fun speedAt(p: Profile, v0: Double): (Long) -> Double {
        val v = DoubleArray((p.totalMs + 1).toInt())
        v[0] = v0
        for (t in 1..p.totalMs.toInt()) v[t] = maxOf(0.0, v[t - 1] + p.accel(t.toLong()) * 3.6 / 1000.0)
        return { t -> v[t.coerceIn(0, p.totalMs).toInt()] }
    }

    private data class Synthetic(val linear: List<Vec3Sample>, val gravity: List<Vec3Sample>, val speed: List<SpeedSample>)

    private fun synth(
        profile: Profile,
        v0: Double,
        mount: Mount,
        seed: Int = 1,
        noise: Double = 0.15,
        obdStepMs: Long = 1_700,
        t0: Long = 1_700_000_000_000L,
    ): Synthetic {
        val rnd = Random(seed)
        val v = speedAt(profile, v0)
        val linear = mutableListOf<Vec3Sample>()
        var t = 0L
        while (t <= profile.totalMs) {
            val along = profile.accel(t)
            // Lateral sway and vertical road bumps (short 10 Hz bursts) that the projection and filter must reject.
            val alat = 0.4 * sin(2 * PI * t / 7_000.0)
            val bump = if ((t / 1000) % 9 == 0L) 2.5 * sin(2 * PI * 10 * t / 1000.0) else 0.0
            val (x, y, z) = mount.toDevice(
                along + noise * rnd.nextDouble(-1.0, 1.0),
                alat + noise * rnd.nextDouble(-1.0, 1.0),
                bump + noise * rnd.nextDouble(-1.0, 1.0),
            )
            linear += Vec3Sample(t0 + t, x, y, z)
            t += 20
        }
        val gravity = (0..profile.totalMs step 1_000).map { gt ->
            val (x, y, z) = mount.toDevice(0.0, 0.0, g)
            Vec3Sample(t0 + gt, x, y, z)
        }
        // K-Line style: one speed sample every ~1.7 s with jitter, 1 km/h resolution.
        val speed = mutableListOf<SpeedSample>()
        var st = 300L
        while (st <= profile.totalMs) {
            speed += SpeedSample(t0 + st, floor(v(st) + 0.5))
            st += obdStepMs + rnd.nextLong(-150, 150)
        }
        return Synthetic(linear, gravity, speed)
    }

    /** Normal driving with [n] braking pulses at 0.35 g (3 s each) and gentle re-accelerations. */
    private fun drivingWithBrakes(n: Int): Profile {
        val segs = mutableListOf<Pair<Long, Double>>()
        repeat(n) { i ->
            segs += 40_000L to 0.0
            segs += 10_000L to 0.03 * (i % 3 - 1) * g
            segs += 3_000L to -0.35 * g
            segs += 15_000L to 0.07 * g
            segs += 10_000L to -0.05 * g
            segs += 9_500L to 0.06 * g
        }
        segs += 30_000L to 0.0
        return Profile(segs)
    }

    @Test
    fun `speed windows follow the DrivingStyle rule`() {
        val pts = listOf(SpeedSample(0, 50.0), SpeedSample(600, 50.0), SpeedSample(1_700, 45.0), SpeedSample(20_000, 0.0), SpeedSample(21_100, 0.0))
        val w = SpeedWindows.derive(pts)
        // 1 700 has ref 600? No: ref = latest sample at least 1 s back = 600 (1 100 ms). The 20 000 sample follows a gap.
        assertEquals(listOf(SpeedWindow(600, 1_700, 50.0, 45.0), SpeedWindow(20_000, 21_100, 0.0, 0.0)), w)
        assertEquals(-5 / 3.6 / 1.1, w[0].accelMs2, 1e-9)
    }

    @Test
    fun `axis is found for an arbitrary mount and 0_35 g brakings give a ratio near 1`() {
        val s = synth(drivingWithBrakes(7), v0 = 100.0, mount = Mount(yawDeg = 37.0, pitchDeg = 63.0, rollDeg = -12.0))
        val r = AccelCalibrationRules.calibrate(s.linear, s.gravity, s.speed)
        val axis = r.axis!!
        assertTrue("axis confidence ${axis.correlation}", axis.correlation > AccelCalibrationThresholds.MIN_AXIS_CORRELATION)
        assertNull("gap ${r.gap}", r.gap)
        val brakes = r.events.filter { it.sensorPeakMs2 < 0 }
        assertEquals(7, brakes.size)
        assertEquals(0, r.events.count { it.sensorPeakMs2 > 0 })
        brakes.forEach { assertEquals(-0.35 * g, it.sensorPeakMs2, 0.06 * g) }
        val median = r.medianRatio!!
        assertTrue("median ratio $median", median in 0.75..1.15)
        assertEquals(StyleThresholds.HARSH_BRAKE_MS2 * median, r.recommendedMs2!!, 1e-9)
        assertTrue(r.ratioQ1!! <= median && median <= r.ratioQ3!!)
    }

    @Test
    fun `the forward sign comes from the OBD speed, not from the mount`() {
        // Phone upside down and turned backwards: forward braking must still read negative.
        val s = synth(drivingWithBrakes(5), v0 = 100.0, mount = Mount(yawDeg = 190.0, pitchDeg = 170.0, rollDeg = 5.0), seed = 3)
        val r = AccelCalibrationRules.calibrate(s.linear, s.gravity, s.speed)
        assertNull("gap ${r.gap}", r.gap)
        assertTrue(r.events.all { it.sensorPeakMs2 < 0 })
    }

    @Test
    fun `phone lying still with noise is not enough data`() {
        val p = Profile(listOf(1_800_000L to 0.0))
        val s = synth(p, v0 = 0.0, mount = Mount(10.0, 0.0, 0.0), noise = 0.05)
        val r = AccelCalibrationRules.calibrate(s.linear, s.gravity, s.speed)
        assertEquals(CalibrationGap.NO_MOVEMENT, r.gap)
        assertTrue(r.events.isEmpty())
        assertNull(r.recommendedMs2)
    }

    @Test
    fun `calm driving finds the axis but has too few events`() {
        val segs = List(20) { i -> 20_000L to (if (i % 2 == 0) 0.08 else -0.08) * g }
        val s = synth(Profile(segs), v0 = 60.0, mount = Mount(80.0, 30.0, 0.0), seed = 5)
        val r = AccelCalibrationRules.calibrate(s.linear, s.gravity, s.speed)
        assertNotNull(r.axis)
        assertEquals(CalibrationGap.TOO_FEW_EVENTS, r.gap)
        assertNull(r.recommendedMs2)
    }

    @Test
    fun `no sensor or no speed data is reported`() {
        assertEquals(CalibrationGap.NO_SENSOR_DATA, AccelCalibrationRules.calibrate(emptyList(), emptyList(), listOf(SpeedSample(0, 1.0))).gap)
        val s = synth(drivingWithBrakes(1), 60.0, Mount(0.0, 0.0, 0.0))
        assertEquals(CalibrationGap.NO_SPEED, AccelCalibrationRules.calibrate(s.linear, s.gravity, emptyList()).gap)
        assertEquals(CalibrationGap.NO_GRAVITY, AccelCalibrationRules.calibrate(s.linear, emptyList(), s.speed).gap)
    }

    @Test
    fun `short bumps above 0_3 g are not events`() {
        // 200 ms spikes of 0.6 g along the car axis: the median filter and the 0.5 s minimum duration drop them.
        val segs = mutableListOf<Pair<Long, Double>>()
        repeat(10) { segs += 9_800L to 0.0; segs += 200L to 0.6 * g }
        val s = synth(Profile(segs), v0 = 50.0, mount = Mount(0.0, 0.0, 0.0))
        val r = AccelCalibrationRules.calibrate(s.linear, s.gravity, s.speed)
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun `per-driver threshold is mean plus two sigma of the windowed derivative magnitude`() {
        // Alternating ±1 m/s² windows: |a| = 1 everywhere → μ = 1, σ = 0.
        val pts = (0..200).map { i -> SpeedSample(i * 1_000L, 50.0 + if (i % 2 == 0) 0.0 else 3.6) }
        val d = DriverThresholds.of(listOf(pts))!!
        assertEquals(1.0, d.meanMs2, 1e-9)
        assertEquals(0.0, d.sdMs2, 1e-9)
        assertEquals(1.0, d.thresholdMs2, 1e-9)
        assertEquals(200, d.windows)
        assertNull(DriverThresholds.of(listOf(pts.take(10))))
    }

    @Test
    fun `csv rows carry the projected axis and the OBD derivative on the 10 Hz timeline`() {
        val s = synth(drivingWithBrakes(5), v0 = 100.0, mount = Mount(37.0, 63.0, -12.0))
        val r = AccelCalibrationRules.calibrate(s.linear, s.gravity, s.speed)
        val rows = AccelCalibrationRules.rows(s.linear, s.gravity, s.speed)
        assertTrue(rows.size > 1000)
        assertEquals(100L, rows[1].tMs - rows[0].tMs)
        val inBrake = rows.filter { it.aLongMs2 != null && it.aLongMs2!! < -0.3 * g }
        assertTrue(inBrake.isNotEmpty())
        assertTrue(inBrake.mapNotNull { it.obdDerivativeMs2 }.min() < -0.25 * g)
        assertNotNull(r.axis)
    }

    @Test
    fun `english summary has the numbers and no UI text`() {
        val s = synth(drivingWithBrakes(6), v0 = 100.0, mount = Mount(37.0, 63.0, -12.0))
        val text = AccelCalibrationRules.english(AccelCalibrationRules.calibrate(s.linear, s.gravity, s.speed))
        assertTrue(text, text.contains("events 6"))
        assertTrue(text, text.contains("recommended"))
        val gap = AccelCalibrationRules.english(AccelCalibrationRules.calibrate(emptyList(), emptyList(), emptyList()))
        assertTrue(gap, gap.startsWith("not enough data"))
        assertEquals(gap, gap.filter { it.code < 128 })
    }
}

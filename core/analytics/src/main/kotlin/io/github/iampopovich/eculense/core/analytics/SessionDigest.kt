package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.analytics.SensorIds.COOLANT
import io.github.iampopovich.eculense.core.analytics.SensorIds.ELM_VOLTAGE
import io.github.iampopovich.eculense.core.analytics.SensorIds.MAF
import io.github.iampopovich.eculense.core.analytics.SensorIds.MIL_DISTANCE
import io.github.iampopovich.eculense.core.analytics.SensorIds.MODULE_VOLTAGE
import io.github.iampopovich.eculense.core.analytics.SensorIds.RPM
import io.github.iampopovich.eculense.core.analytics.SensorIds.SPEED
import io.github.iampopovich.eculense.core.analytics.Thresholds.COLD_START_MAX_C
import io.github.iampopovich.eculense.core.analytics.Thresholds.COOLANT_MAX_AGE_MS
import io.github.iampopovich.eculense.core.analytics.Thresholds.IDLE_MAX_RPM
import io.github.iampopovich.eculense.core.analytics.Thresholds.OVERHEAT_CRIT_C
import io.github.iampopovich.eculense.core.analytics.Thresholds.OVERHEAT_WARN_C
import io.github.iampopovich.eculense.core.analytics.Thresholds.RUNNING_RPM
import io.github.iampopovich.eculense.core.analytics.Thresholds.STATE_MAX_AGE_MS
import io.github.iampopovich.eculense.core.analytics.Thresholds.VOLTAGE_HIGH_V
import io.github.iampopovich.eculense.core.analytics.Thresholds.VOLTAGE_LOW_V
import io.github.iampopovich.eculense.core.analytics.Thresholds.WARM_COOLANT_C
import io.github.iampopovich.eculense.core.model.SensorReading
import kotlin.math.roundToInt

/**
 * Сжатая сводка одной сессии — всё, что нужно правилам. Считается одним проходом по значениям,
 * отсортированным по времени; между сессиями складывается через [Agg]/[Histogram], поэтому пересчёт
 * по всей истории не держит в памяти миллион значений.
 */
data class SessionDigest(
    val sessionId: Long,
    val startedAtMs: Long,
    val coolant: Agg = Agg.EMPTY,
    /** Самый долгий отрезок подряд с ОЖ ≥ 105 °C. */
    val overheatWarnMs: Long = 0,
    /** Самый долгий отрезок подряд с ОЖ ≥ 110 °C. */
    val overheatCritMs: Long = 0,
    /** Чьё напряжение использовано: [MODULE_VOLTAGE], если есть, иначе [ELM_VOLTAGE]. */
    val voltageSensor: String? = null,
    val runningVoltage: Agg = Agg.EMPTY,
    val runningVoltageHist: Histogram = Histogram.EMPTY,
    val lowVoltageMs: Long = 0,
    val highVoltageMs: Long = 0,
    /** Коррекции 01.06–01.09 на прогретом двигателе. */
    val warmTrims: Map<String, Agg> = emptyMap(),
    /** MAF на прогретом холостом ходу (скорость 0, обороты 400–1100). */
    val warmIdleMaf: Agg = Agg.EMPTY,
    val milDistanceKm: Agg = Agg.EMPTY,
    /** ОЖ в первом замере записи. */
    val coldStartC: Double? = null,
    /** От первого замера ОЖ до 80 °C — только для холодного пуска (≤ 30 °C). */
    val warmupMs: Long? = null,
)

object SessionDigester {
    /** [readings] — только нужные правилам датчики ([SensorIds.FOR_RULES]), отсортированные по времени. */
    fun digest(sessionId: Long, startedAtMs: Long, readings: Iterable<SensorReading>): SessionDigest {
        val acc = Accumulator()
        readings.forEach(acc::accept)
        return acc.build(sessionId, startedAtMs)
    }

    /** Изменяемый накопитель: один проход, без копий коллекций на каждом значении. */
    class Accumulator {
        private var rpm = Double.NaN
        private var rpmAt = Long.MIN_VALUE
        private var speed = Double.NaN
        private var speedAt = Long.MIN_VALUE
        private var coolantNow = Double.NaN
        private var coolantAt = Long.MIN_VALUE

        private val coolant = MutableAgg()
        private val overheatWarn = RunTracker()
        private val overheatCrit = RunTracker()
        private var firstCoolant: Double? = null
        private var firstCoolantAt = 0L
        private var warmupMs: Long? = null

        private val voltage = mapOf(MODULE_VOLTAGE to VoltageAcc(), ELM_VOLTAGE to VoltageAcc())
        private val trims = HashMap<String, MutableAgg>()
        private val idleMaf = MutableAgg()
        private val mil = MutableAgg()

        private fun fresh(at: Long, now: Long, maxAge: Long) = at != Long.MIN_VALUE && now - at <= maxAge
        private fun running(now: Long) = fresh(rpmAt, now, STATE_MAX_AGE_MS) && rpm > RUNNING_RPM
        private fun warm(now: Long) = fresh(coolantAt, now, COOLANT_MAX_AGE_MS) && coolantNow >= WARM_COOLANT_C

        fun accept(r: SensorReading) {
            val v = r.value
            if (v.isNaN() || v.isInfinite()) return
            val t = r.timestampMs
            when (r.sensorId) {
                RPM -> { rpm = v; rpmAt = t }
                SPEED -> { speed = v; speedAt = t }
                COOLANT -> {
                    coolantNow = v; coolantAt = t
                    coolant.add(v)
                    overheatWarn.update(t, v >= OVERHEAT_WARN_C)
                    overheatCrit.update(t, v >= OVERHEAT_CRIT_C)
                    if (firstCoolant == null) { firstCoolant = v; firstCoolantAt = t }
                    val start = firstCoolant!!
                    if (warmupMs == null && start <= COLD_START_MAX_C && v >= WARM_COOLANT_C) warmupMs = t - firstCoolantAt
                }
                MODULE_VOLTAGE, ELM_VOLTAGE -> voltage.getValue(r.sensorId).accept(t, v, running(t))
                MAF -> {
                    val idle = fresh(speedAt, t, STATE_MAX_AGE_MS) && speed == 0.0 && running(t) && rpm <= IDLE_MAX_RPM
                    if (idle && warm(t)) idleMaf.add(v)
                }
                MIL_DISTANCE -> mil.add(v)
                in SensorIds.TRIMS -> if (warm(t)) trims.getOrPut(r.sensorId) { MutableAgg() }.add(v)
            }
        }

        fun build(sessionId: Long, startedAtMs: Long): SessionDigest {
            val (vId, vAcc) = voltage.entries.firstOrNull { it.value.agg.count > 0 }?.toPair()
                ?: (null to null)
            return SessionDigest(
                sessionId = sessionId,
                startedAtMs = startedAtMs,
                coolant = coolant.toAgg(),
                overheatWarnMs = overheatWarn.longest,
                overheatCritMs = overheatCrit.longest,
                voltageSensor = vId,
                runningVoltage = vAcc?.agg?.toAgg() ?: Agg.EMPTY,
                runningVoltageHist = vAcc?.histogram() ?: Histogram.EMPTY,
                lowVoltageMs = vAcc?.low?.longest ?: 0,
                highVoltageMs = vAcc?.high?.longest ?: 0,
                warmTrims = trims.mapValues { it.value.toAgg() },
                warmIdleMaf = idleMaf.toAgg(),
                milDistanceKm = mil.toAgg(),
                coldStartC = firstCoolant,
                warmupMs = warmupMs,
            )
        }
    }

    private class MutableAgg {
        var count = 0
        var sum = 0.0
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        fun add(v: Double) { count++; sum += v; if (v < min) min = v; if (v > max) max = v }
        fun toAgg() = Agg(count, sum, min, max)
    }

    /** Самый длинный отрезок, на котором условие выполнялось на каждом замере подряд. */
    private class RunTracker {
        private var start = Long.MIN_VALUE
        var longest = 0L
            private set

        fun update(t: Long, condition: Boolean) {
            if (!condition) { start = Long.MIN_VALUE; return }
            if (start == Long.MIN_VALUE) start = t
            if (t - start > longest) longest = t - start
        }
    }

    /** Напряжение считается только на работающем двигателе: стартер и выключенное зажигание — не недозаряд. */
    private class VoltageAcc {
        val agg = MutableAgg()
        val low = RunTracker()
        val high = RunTracker()
        private val bins = HashMap<Int, Int>()

        fun accept(t: Long, v: Double, running: Boolean) {
            if (!running) {
                low.update(t, false); high.update(t, false)
                return
            }
            agg.add(v)
            val bin = (v * 10).roundToInt()
            bins[bin] = (bins[bin] ?: 0) + 1
            low.update(t, v < VOLTAGE_LOW_V)
            high.update(t, v > VOLTAGE_HIGH_V)
        }

        fun histogram() = Histogram(bins.toMap())
    }
}

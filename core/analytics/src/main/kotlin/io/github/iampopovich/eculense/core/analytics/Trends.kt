package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.model.Trip
import io.github.iampopovich.eculense.core.model.TripStats
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class TrendBucket { MONTH, WEEK }

/**
 * Одна корзина тренда (месяц или неделя, по местному времени; крайние обрезаны периодом).
 * Числа по датчикам — только где датчик есть: null = в корзине нет замеров, а не «ноль».
 */
data class BucketTrend(
    /** Месяц «2026-01» или дата начала недели «2026-01-05». */
    val label: String,
    val fromMs: Long,
    val toMs: Long,
    val trips: TripStats,
    /** Доля времени на месте (стоянка с работающим мотором по данным поездки) от всего времени поездок. */
    val idleShare: Double?,
    val avgTripKm: Double?,
    /** Записей (сессий OBD) в корзине. */
    val sessions: Int,
    val coolantN: Int,
    val coolantMax: Double?,
    val coolantMedian: Double?,
    val voltageN: Int,
    val voltageMedian: Double?,
    /** Минимум напряжения на работающем двигателе (стартер не считается). */
    val voltageMinRunning: Double?,
    /** Медианы коррекций на прогретом двигателе по id датчика (01.06–01.09). */
    val trimMedians: Map<String, Double>,
    val trimN: Map<String, Int>,
    val idleRpmN: Int,
    val idleRpmMedian: Double?,
    /** Событий DTC (код × момент чтения) в корзине. */
    val dtcEvents: Int,
    /** Коды, впервые появившиеся в истории именно в этой корзине. */
    val newCodes: List<String>,
) {
    val empty: Boolean get() = trips.trips == 0 && sessions == 0 && dtcEvents == 0
}

/** Изменение метрики между первой и последней корзиной, где данных достаточно. */
data class TrendChange(val metric: String, val fromLabel: String, val toLabel: String, val from: Double, val to: Double) {
    val delta: Double get() = to - from
}

data class TrendReport(
    val days: Int,
    val bucket: TrendBucket,
    /** От старых к новым. */
    val buckets: List<BucketTrend>,
    val totals: TripStats,
    val changes: List<TrendChange>,
    /** Метрики, для которых меньше двух корзин с достаточными данными. */
    val insufficient: List<String>,
)

/**
 * Тренды за произвольный период: детерминированно из поездок, дайджестов сессий ([SessionDigest]) и истории DTC.
 * Замеры датчиков не грузятся — медианы берутся из гистограмм дайджестов.
 */
object Trends {
    private const val DAY_MS = 86_400_000L

    /** Меньше замеров датчика в корзине — для «изменения» корзина не годится. */
    const val MIN_SAMPLES = 30

    /** Меньше поездок в корзине — расход/доля стоянки/длина поездки не сравниваются. */
    const val MIN_TRIPS = 2

    /** Неделя, если период ≤ 45 дней, иначе месяц. */
    fun defaultBucket(days: Int): TrendBucket = if (days <= 45) TrendBucket.WEEK else TrendBucket.MONTH

    /** Порядок метрик «изменения» (он же — в ответе инструмента). */
    val CHANGE_METRICS = listOf(
        "l_per_100km", "avg_trip_km", "idle_share", "coolant_median", "coolant_max", "voltage_median", "voltage_min_running",
        "ltft1_median", "ltft2_median", "idle_rpm_median",
    )

    fun build(
        days: Int,
        bucket: TrendBucket?,
        nowMs: Long,
        zone: ZoneId,
        trips: List<Trip>,
        digests: List<SessionDigest>,
        dtc: List<DtcEvent>,
    ): TrendReport {
        val d = days.coerceAtLeast(1)
        val kind = bucket ?: defaultBucket(d)
        val fromMs = nowMs - d * DAY_MS
        val firstSeen = dtc.groupBy { it.code }.mapValues { (_, l) -> l.minOf { it.atMs } }
        val inPeriodTrips = trips.filter { it.startedAtMs in fromMs..nowMs }
        val buckets = ranges(fromMs, nowMs, zone, kind).map { (label, from, to) ->
            bucket(
                label, from, to,
                inPeriodTrips.filter { it.startedAtMs in from..to },
                digests.filter { it.startedAtMs in from..to },
                dtc.filter { it.atMs in from..to },
                firstSeen,
            )
        }
        val changes = mutableListOf<TrendChange>()
        val insufficient = mutableListOf<String>()
        for (metric in CHANGE_METRICS) {
            val points = buckets.mapNotNull { b -> metricValue(b, metric)?.let { b.label to it } }
            if (points.size < 2) insufficient += metric
            else changes += TrendChange(metric, points.first().first, points.last().first, points.first().second, points.last().second)
        }
        return TrendReport(d, kind, buckets, TripStats.of(inPeriodTrips), changes, insufficient)
    }

    /** Значение метрики корзины, только если данных достаточно для сравнения. */
    private fun metricValue(b: BucketTrend, metric: String): Double? {
        val tripsOk = b.trips.trips >= MIN_TRIPS
        return when (metric) {
            "l_per_100km" -> b.trips.avgLPer100Km.takeIf { tripsOk }
            "avg_trip_km" -> b.avgTripKm.takeIf { tripsOk }
            "idle_share" -> b.idleShare.takeIf { tripsOk }
            "coolant_median" -> b.coolantMedian.takeIf { b.coolantN >= MIN_SAMPLES }
            "coolant_max" -> b.coolantMax.takeIf { b.coolantN >= MIN_SAMPLES }
            "voltage_median" -> b.voltageMedian.takeIf { b.voltageN >= MIN_SAMPLES }
            "voltage_min_running" -> b.voltageMinRunning.takeIf { b.voltageN >= MIN_SAMPLES }
            "ltft1_median" -> b.trimMedians[SensorIds.LTFT1].takeIf { (b.trimN[SensorIds.LTFT1] ?: 0) >= MIN_SAMPLES }
            "ltft2_median" -> b.trimMedians[SensorIds.LTFT2].takeIf { (b.trimN[SensorIds.LTFT2] ?: 0) >= MIN_SAMPLES }
            "idle_rpm_median" -> b.idleRpmMedian.takeIf { b.idleRpmN >= MIN_SAMPLES }
            else -> null
        }
    }

    private fun bucket(
        label: String,
        from: Long,
        to: Long,
        trips: List<Trip>,
        digests: List<SessionDigest>,
        dtc: List<DtcEvent>,
        firstSeen: Map<String, Long>,
    ): BucketTrend {
        val stats = TripStats.of(trips)
        val totalMs = stats.drivingMs + stats.standingMs
        val coolant = digests.map { it.coolant }.merged()
        val coolantHist = digests.fold(Histogram.EMPTY) { h, dg -> h + dg.coolantHist }
        val voltage = digests.map { it.runningVoltage }.merged()
        val voltageHist = digests.fold(Histogram.EMPTY) { h, dg -> h + dg.runningVoltageHist }
        val trimHist = SensorIds.TRIMS.associateWith { id -> digests.fold(Histogram.EMPTY) { h, dg -> h + (dg.warmTrimHist[id] ?: Histogram.EMPTY) } }
            .filterValues { it.count > 0 }
        val idleHist = digests.fold(Histogram.EMPTY) { h, dg -> h + dg.warmIdleRpmHist }
        return BucketTrend(
            label = label, fromMs = from, toMs = to,
            trips = stats,
            idleShare = if (totalMs > 0) stats.standingMs.toDouble() / totalMs else null,
            avgTripKm = if (stats.trips > 0) stats.distanceKm / stats.trips else null,
            sessions = digests.size,
            coolantN = coolant.count,
            coolantMax = coolant.max.takeIf { coolant.count > 0 },
            coolantMedian = coolantHist.quantile(0.5),
            voltageN = voltage.count,
            voltageMedian = voltageHist.quantile(0.5),
            voltageMinRunning = voltage.min.takeIf { voltage.count > 0 },
            trimMedians = trimHist.mapValues { it.value.quantile(0.5)!! },
            trimN = trimHist.mapValues { it.value.count },
            idleRpmN = idleHist.count,
            idleRpmMedian = idleHist.quantile(0.5),
            dtcEvents = dtc.map { it.code to it.atMs }.distinct().size,
            newCodes = dtc.map { it.code }.distinct().filter { firstSeen[it] in from..to }.sorted(),
        )
    }

    /** Корзины [from, to] (включительно), обрезанные периодом; метка — месяц или дата начала (обрезанного) отрезка. */
    private fun ranges(fromMs: Long, nowMs: Long, zone: ZoneId, kind: TrendBucket): List<Triple<String, Long, Long>> {
        fun startOf(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val firstDay = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
        val lastDay = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val out = mutableListOf<Triple<String, Long, Long>>()
        when (kind) {
            TrendBucket.MONTH -> {
                var m = YearMonth.from(firstDay)
                while (!m.isAfter(YearMonth.from(lastDay))) {
                    val from = maxOf(startOf(m.atDay(1)), fromMs)
                    val to = minOf(startOf(m.plusMonths(1).atDay(1)) - 1, nowMs)
                    out += Triple(m.toString(), from, to)
                    m = m.plusMonths(1)
                }
            }
            TrendBucket.WEEK -> {
                var monday = firstDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                while (!monday.isAfter(lastDay)) {
                    val from = maxOf(startOf(monday), fromMs)
                    val to = minOf(startOf(monday.plusWeeks(1)) - 1, nowMs)
                    val label = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().toString()
                    out += Triple(label, from, to)
                    monday = monday.plusWeeks(1)
                }
            }
        }
        return out
    }
}

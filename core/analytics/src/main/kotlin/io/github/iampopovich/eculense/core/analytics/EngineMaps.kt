package io.github.iampopovich.eculense.core.analytics

/** One scatter point of D5: total fuel trim (STFT + LTFT) of [bank] at the engine speed and load of that moment. */
data class TrimPoint(val bank: Int, val rpm: Double, val load: Double?, val trim: Double)

/** One bin row: trims of [bank] with the axis value in [from]..<[to] (to = null: open end). */
data class TrimBin(val bank: Int, val from: Double, val to: Double?, val count: Int, val median: Double?)

enum class MapAxis { RPM, LOAD }

/**
 * Hypotheses from the shape of the trims across operating modes. Wording is careful on purpose: a pattern is «typical
 * for», never a diagnosis — the UI and the AI present it as something to check.
 */
enum class EngineMapPattern {
    /** Trims high at idle and dropping under load: typical for unmetered air (vacuum leak after the MAF). */
    AIR_LEAK_LIKE,
    /** Trims rising under load while idle is fine: typical for weak fuel delivery (pump, filter, regulator). */
    FUEL_DELIVERY_LIKE,
    /** Lean everywhere: MAF reading low, fuel pressure, or a big leak. */
    LEAN_EVERYWHERE,
    /** Rich everywhere: leaking injectors, fuel pressure too high, MAF reading high. */
    RICH_EVERYWHERE,
    /** Idle and load trims within the normal band, no clear slope. */
    NORMAL,
    /** Not enough warm samples at idle and under load. */
    NOT_ENOUGH_DATA,
}

data class EngineMapSummary(
    val pattern: EngineMapPattern,
    val idleMedian: Double?,
    val loadMedian: Double?,
    val idleCount: Int,
    val loadCount: Int,
    /** Which axis decided «under load»: engine load (01.04) when recorded, else rpm. */
    val loadAxis: MapAxis,
)

data class EngineMap(
    val points: List<TrimPoint>,
    val rpmBins: List<TrimBin>,
    val loadBins: List<TrimBin>,
    val summary: EngineMapSummary,
) {
    val banks: List<Int> get() = points.map { it.bank }.distinct().sorted()
}

/**
 * D5 «engine maps» from one recording. Each STFT sample is paired with the bank's latest LTFT, rpm and load if they are at
 * most [MAX_AGE_MS] old (K-Line cycles are ~1.7 s); samples on a cold engine (coolant under [WARM_C], open loop) are
 * skipped when coolant is recorded. Bins and medians are plain data for the UI table and the AI tool.
 */
object EngineMaps {
    const val LOAD = "01.04"
    const val MAX_AGE_MS = 4_000L
    const val WARM_C = 70.0
    const val MIN_BIN = 5
    val RPM_EDGES = listOf(0.0, 1_000.0, 1_500.0, 2_000.0, 2_500.0, 3_000.0, 4_000.0)
    val LOAD_EDGES = listOf(0.0, 20.0, 40.0, 60.0, 80.0)
    /** «Idle»: below this rpm. «Under load»: load ≥ [LOAD_FROM] %, or rpm ≥ [LOAD_RPM_FROM] without a load PID. */
    const val IDLE_RPM = 1_000.0
    const val LOAD_FROM = 50.0
    const val LOAD_RPM_FROM = 2_500.0
    /** A trim at or beyond this (percent) counts as «high» for the patterns. */
    const val HIGH_TRIM = 8.0
    /** Idle vs load difference that makes a slope. */
    const val SLOPE = 6.0

    val SENSORS: Set<String> = setOf(SensorIds.STFT1, SensorIds.LTFT1, SensorIds.STFT2, SensorIds.LTFT2, SensorIds.RPM, LOAD, SensorIds.COOLANT)

    fun build(readings: Iterable<Triple<String, Double, Long>>): EngineMap {
        val last = HashMap<String, Pair<Double, Long>>()
        val points = mutableListOf<TrimPoint>()
        var coolantSeen = false
        for ((id, v, t) in readings) {
            if (id == SensorIds.COOLANT) coolantSeen = true
            last[id] = v to t
            val bank = when (id) {
                SensorIds.STFT1 -> 1
                SensorIds.STFT2 -> 2
                else -> continue
            }
            fun fresh(s: String) = last[s]?.takeIf { t - it.second <= MAX_AGE_MS }?.first
            val ltft = fresh(if (bank == 1) SensorIds.LTFT1 else SensorIds.LTFT2) ?: continue
            val rpm = fresh(SensorIds.RPM) ?: continue
            if (coolantSeen && (fresh(SensorIds.COOLANT) ?: last[SensorIds.COOLANT]!!.first) < WARM_C) continue
            if (rpm < 300) continue // engine off / cranking
            points += TrimPoint(bank, rpm, fresh(LOAD), v + ltft)
        }
        return EngineMap(points, bins(points, RPM_EDGES) { it.rpm }, bins(points.filter { it.load != null }, LOAD_EDGES) { it.load!! }, summarize(points))
    }

    private fun bins(points: List<TrimPoint>, edges: List<Double>, axis: (TrimPoint) -> Double): List<TrimBin> =
        points.map { it.bank }.distinct().sorted().flatMap { bank ->
            val mine = points.filter { it.bank == bank }
            edges.indices.map { i ->
                val from = edges[i]
                val to = edges.getOrNull(i + 1)
                val values = mine.filter { val x = axis(it); x >= from && (to == null || x < to) }.map { it.trim }
                TrimBin(bank, from, to, values.size, if (values.size >= MIN_BIN) FuelLog.median(values) else null)
            }
        }

    fun summarize(points: List<TrimPoint>): EngineMapSummary {
        val hasLoad = points.count { it.load != null } >= MIN_BIN
        val idle = points.filter { it.rpm < IDLE_RPM }.map { it.trim }
        val load = points.filter { if (hasLoad) (it.load ?: -1.0) >= LOAD_FROM else it.rpm >= LOAD_RPM_FROM }.map { it.trim }
        val axis = if (hasLoad) MapAxis.LOAD else MapAxis.RPM
        val i = idle.takeIf { it.size >= MIN_BIN }?.let(FuelLog::median)
        val l = load.takeIf { it.size >= MIN_BIN }?.let(FuelLog::median)
        val pattern = when {
            i == null || l == null -> EngineMapPattern.NOT_ENOUGH_DATA
            i >= HIGH_TRIM && l <= i - SLOPE -> EngineMapPattern.AIR_LEAK_LIKE
            i >= HIGH_TRIM && l >= HIGH_TRIM -> EngineMapPattern.LEAN_EVERYWHERE
            l >= HIGH_TRIM && l - i >= SLOPE -> EngineMapPattern.FUEL_DELIVERY_LIKE
            i <= -HIGH_TRIM && l <= -HIGH_TRIM -> EngineMapPattern.RICH_EVERYWHERE
            else -> EngineMapPattern.NORMAL
        }
        return EngineMapSummary(pattern, i, l, idle.size, load.size, axis)
    }

    /** Plain English hypothesis for the AI (never a diagnosis). */
    fun english(p: EngineMapPattern): String = when (p) {
        EngineMapPattern.AIR_LEAK_LIKE -> "Hypothesis: trims are high at idle and drop under load - typical for unmetered air (vacuum leak after the MAF). Check, don't conclude."
        EngineMapPattern.FUEL_DELIVERY_LIKE -> "Hypothesis: trims rise under load while idle is normal - typical for weak fuel delivery (pump, filter, pressure regulator)."
        EngineMapPattern.LEAN_EVERYWHERE -> "Hypothesis: lean in every mode - MAF reading low, low fuel pressure or a large leak."
        EngineMapPattern.RICH_EVERYWHERE -> "Hypothesis: rich in every mode - leaking injectors, high fuel pressure or MAF reading high."
        EngineMapPattern.NORMAL -> "Trims at idle and under load are within the normal band, no clear pattern."
        EngineMapPattern.NOT_ENOUGH_DATA -> "Not enough warm samples at idle and under load to compare."
    }
}

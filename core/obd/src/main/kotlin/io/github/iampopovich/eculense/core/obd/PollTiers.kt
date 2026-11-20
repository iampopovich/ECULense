package io.github.iampopovich.eculense.core.obd

/**
 * How often a PID is read (C2): FAST every polling cycle, MEDIUM every 3rd, SLOW every 10th. On a K-Line car each
 * PID costs ~280 ms, so reading coolant and trims every cycle slows rpm and speed down for nothing.
 */
enum class PollTier(val everyCycles: Int) { FAST(1), MEDIUM(3), SLOW(10) }

/** Tier table (data, not code paths) plus the timing constants of the scheduler. */
object PollTiers {
    private val FAST = listOf(0x04, 0x0C, 0x0D, 0x11)

    // MAF, MAP, IAT, timing, fuel rate, lambda, relative throttle, all O2 sensors (narrow and wide band).
    private val MEDIUM = listOf(0x0B, 0x0E, 0x0F, 0x10, 0x44, 0x45, 0x5E) +
        (0x14..0x1B) + (0x24..0x2B) + (0x34..0x3B)

    val defaults: Map<Int, PollTier> =
        FAST.associateWith { PollTier.FAST } + MEDIUM.associateWith { PollTier.MEDIUM }

    /** Everything not listed (coolant, trims, fuel level, baro, ambient, module voltage, counters...) is SLOW. */
    fun tierOf(code: Int): PollTier = defaults[code] ?: PollTier.SLOW

    /** With the screen off / app in background (recording only) MEDIUM and SLOW are read half as often. */
    const val BACKGROUND_FACTOR = 2

    /** Adapter voltage (ATRV) is read about this often while polling; recorded as [ADAPTER_VOLTAGE_ID]. */
    const val ADAPTER_VOLTAGE_EVERY_MS = 10_000L

    /** Sensor id of the adapter's ATRV reading (same id as the Car Scanner import uses). */
    const val ADAPTER_VOLTAGE_ID = "elm.voltage"

    fun periodCycles(tier: PollTier, background: Boolean): Int =
        if (background && tier != PollTier.FAST) tier.everyCycles * BACKGROUND_FACTOR else tier.everyCycles
}

/**
 * Which PIDs are due in a cycle. Cycle 0 reads everything once so the screen fills at once; afterwards the i-th
 * member of a tier is due when (cycle + i) is a multiple of the tier period, which spreads a tier over its period
 * instead of reading all slow PIDs in one long cycle.
 */
open class PollScheduler(private val tierOf: (Int) -> PollTier = PollTiers::tierOf) {
    open fun due(cycle: Long, pids: List<Pid>, background: Boolean = false): List<Pid> {
        if (cycle == 0L) return pids
        val indexInTier = mutableMapOf<PollTier, Int>()
        return pids.filter { pid ->
            val tier = tierOf(pid.code)
            val i = indexInTier.getOrDefault(tier, 0)
            indexInTier[tier] = i + 1
            (cycle + i) % PollTiers.periodCycles(tier, background) == 0L
        }
    }
}

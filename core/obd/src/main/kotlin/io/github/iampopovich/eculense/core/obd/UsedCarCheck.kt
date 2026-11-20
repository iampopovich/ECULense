package io.github.iampopovich.eculense.core.obd

/**
 * B2 counters the ECU keeps since the DTCs were last cleared (PIDs 30, 31, 4E) and while the Check Engine lamp is on
 * (4D, 21). null = the ECU does not support that PID.
 */
data class ClearedCounters(
    val warmUpsSinceClear: Int? = null,
    val kmSinceClear: Int? = null,
    val minutesSinceClear: Int? = null,
    val minutesWithMil: Int? = null,
    val kmWithMil: Int? = null,
) {
    val isEmpty: Boolean
        get() = warmUpsSinceClear == null && kmSinceClear == null && minutesSinceClear == null &&
            minutesWithMil == null && kmWithMil == null

    companion object {
        /** Service 01 PIDs read for the check, in request order. */
        val PIDS = listOf(0x30, 0x31, 0x4E, 0x4D, 0x21)

        /** [data] = data bytes per PID (without "41 pid"). */
        fun decode(data: Map<Int, IntArray>): ClearedCounters {
            fun byte(pid: Int) = data[pid]?.takeIf { it.isNotEmpty() }?.get(0)
            fun word(pid: Int) = data[pid]?.takeIf { it.size >= 2 }?.let { it[0] * 256 + it[1] }
            return ClearedCounters(byte(0x30), word(0x31), word(0x4E), word(0x4D), word(0x21))
        }
    }
}

/** Below these values the codes count as «recently cleared». Data, not code paths, so they can be tuned. */
data class RecentClearThresholds(
    val warmUps: Int = 10,
    val km: Int = 200,
    val minutes: Int = 300,
) {
    companion object {
        val DEFAULT = RecentClearThresholds()
    }
}

enum class RecentClearVerdict {
    /** Counters are low AND some monitors are incomplete: typical right after a reset (or a battery disconnect). */
    LIKELY_CLEARED,

    /** Only one of the two signs (low counters, or incomplete monitors). */
    POSSIBLY_CLEARED,

    /** Counters are high and every supported monitor is complete. */
    NO_SIGNS,

    /** The ECU gave neither counters nor readiness. */
    UNKNOWN,
}

/** Which inputs led to the verdict, so the UI can name them. */
data class RecentClearCheck(
    val verdict: RecentClearVerdict,
    val counters: ClearedCounters,
    val incompleteMonitors: Int?,
    val lowCounters: Boolean,
)

/** Deterministic used-car rule (B2): never a guess from text, only from the numbers above. */
object UsedCarCheck {
    fun evaluate(
        counters: ClearedCounters,
        readiness: Readiness?,
        thresholds: RecentClearThresholds = RecentClearThresholds.DEFAULT,
    ): RecentClearCheck {
        val low = listOfNotNull(
            counters.warmUpsSinceClear?.let { it < thresholds.warmUps },
            counters.kmSinceClear?.let { it < thresholds.km },
            counters.minutesSinceClear?.let { it < thresholds.minutes },
        )
        val incomplete = readiness?.takeIf { it.supported.isNotEmpty() }?.incompleteCount
        val lowCounters = low.any { it }
        val verdict = when {
            low.isEmpty() && incomplete == null -> RecentClearVerdict.UNKNOWN
            lowCounters && (incomplete ?: 0) > 0 -> RecentClearVerdict.LIKELY_CLEARED
            lowCounters || (incomplete ?: 0) > 0 -> RecentClearVerdict.POSSIBLY_CLEARED
            else -> RecentClearVerdict.NO_SIGNS
        }
        return RecentClearCheck(verdict, counters, incomplete, lowCounters)
    }
}

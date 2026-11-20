package io.github.iampopovich.eculense.core.analytics

/**
 * All analytics thresholds in one place. These are guidelines for a naturally aspirated gasoline engine,
 * not factory tolerances of a specific car; the source is given next to each value.
 */
object Thresholds {
    // --- Engine state ---
    /** "Warm engine": coolant ≥ 80 °C (plan 3.8; thermostats open at ~82–88 °C). */
    const val WARM_COOLANT_C = 80.0
    /** "Engine running": RPM above 400 (below — cranking or stopped; plan 3.8). */
    const val RUNNING_RPM = 400.0
    /** Upper idle bound: revving while stationary is no longer idle (typical idle 650–900 rpm). */
    const val IDLE_MAX_RPM = 1_100.0
    /** How long the last RPM/speed value is considered current (K-Line polling cycle ~1.7 s). */
    const val STATE_MAX_AGE_MS = 10_000L
    /** Coolant temperature changes slowly — its value lives longer. */
    const val COOLANT_MAX_AGE_MS = 60_000L

    // --- Coolant overheating ---
    /** Operating temperature 85–100 °C; the fan usually turns on at 95–102 °C. */
    const val OVERHEAT_WARN_C = 105.0
    /** ≥ 110 °C — close to antifreeze boiling under cap pressure (~120 °C), a risk for the cylinder head. */
    const val OVERHEAT_CRIT_C = 110.0
    /** How long to stay above the threshold continuously, to avoid catching single outliers (plan 3.2: 30 s). */
    const val OVERHEAT_MIN_MS = 30_000L

    // --- System voltage with the engine running ---
    /** The alternator holds 13.5–14.7 V; below 13.2 V for over a minute — undercharging (plan 3.8). */
    const val VOLTAGE_LOW_V = 13.2
    const val VOLTAGE_LOW_MIN_MS = 60_000L
    /** Above 14.8 V — overcharging (voltage regulator), harmful to the battery. */
    const val VOLTAGE_HIGH_V = 14.8
    const val VOLTAGE_HIGH_MIN_MS = 10_000L

    // --- Fuel trims (warm engine) ---
    /** Long-term trim within ±10 % is normal (plan 3.8; many ECUs set P0171/P0174 around ±25 %). */
    const val LTFT_LIMIT_PCT = 10.0
    /** STFT+LTFT sum beyond ±25 % — the ECU is at the limit of mixture control. */
    const val TOTAL_TRIM_CRIT_PCT = 25.0
    /** Long-term trim difference between banks of a V engine over 8 % — a problem on one side. */
    const val BANK_ASYMMETRY_PCT = 8.0
    /** Fewer samples — the conclusion is preliminary: severity is not raised to CRITICAL. */
    const val MIN_TRIM_SAMPLES = 10

    // --- Airflow at warm idle ---
    /** Guideline ≈ 0.9–1.3 g/s per liter of displacement (rule parameter, plan 3.8). */
    const val MAF_IDLE_MIN_G_PER_L = 0.9
    const val MAF_IDLE_MAX_G_PER_L = 1.3
    const val MAF_IDLE_MIN_SAMPLES = 5

    // --- Trouble codes ---
    /** Recurring code: ≥ 2 events within 30 days (plan 3.8). */
    const val DTC_REPEAT_WINDOW_MS = 30L * 24 * 3_600_000
    /** Reads of the same code more often than once per 6 hours are the same event (the «Read» button gets pressed several times). */
    const val DTC_SAME_EVENT_MS = 6L * 3_600_000

    // --- Warm-up ---
    /** "Cold start": coolant at the start of the recording ≤ 30 °C. */
    const val COLD_START_MAX_C = 30.0
    /** Median of recent warm-ups 1.3× longer than the earlier one — a trend (thermostat, coolant sensor). */
    const val WARMUP_TREND_RATIO = 1.3
    /** Cold starts needed in each half of the history to talk about a trend. */
    const val WARMUP_MIN_STARTS_PER_HALF = 3

    // --- Live alerts during a connection (C4); defaults the user can change ---
    /** Below 12.0 V with the engine running the alternator does not charge at all (battery alone ~12.2–12.7 V). */
    const val LIVE_NOT_CHARGING_V = 12.0
    /** Above 15.2 V the regulator has failed: lamps and the battery suffer within minutes. */
    const val LIVE_OVERCHARGE_V = 15.2
    /** «Engine running» for the charging check: above cranking/idle-drop territory. */
    const val LIVE_CHARGING_RPM = 500.0
    /** A condition must hold this long before an alert (one bad sample is not an alert). */
    const val LIVE_CONFIRM_MS = 5_000L
    /** Hysteresis: the alert clears only this far back on the safe side of the threshold. */
    const val LIVE_COOLANT_HYSTERESIS_C = 3.0
    const val LIVE_VOLTAGE_HYSTERESIS_V = 0.3
    /** One heads-up notification per kind at most this often. */
    const val LIVE_NOTIFY_COOLDOWN_MS = 10 * 60_000L

    // --- Engine stall (B10) ---
    /** The engine was running (rpm above [RUNNING_RPM] or speed above 0) at most this long before rpm hit 0. */
    const val STALL_LOOKBACK_MS = 10_000L
    /** Voltage at the OBD port that still means «ignition on» (a dead port reads ~0). */
    const val STALL_IGNITION_MIN_V = 11.0
    /** Two stalls in the analysed period make a finding (one could be the driver); critical from this many. */
    const val STALL_FINDING_MIN = 2
    const val STALL_FINDING_CRITICAL = 4
}

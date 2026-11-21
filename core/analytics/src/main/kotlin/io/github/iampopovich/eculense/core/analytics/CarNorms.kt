package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.FuelType

/**
 * R-H «norms of the car» (Alex: «every engine is unique; one car's values or generic defaults don't fit other cars»).
 *
 * Every numeric constant of the analytics, health check, live alerts and maintenance falls into one class:
 * - **1 — physics and standards**: stay constants with a cited source (SAE J1979 PID scales, stoichiometry, g).
 * - **2 — universal safety limits**: stay absolute for every car, never capped or flagged as «generic».
 * - **3 — engine-specific norms**: never a global constant. Each is a [NormKey] with a per-car [Norm] and its
 *   [NormSource]; the former constant survives only as the flagged [NormSource.GENERIC] fallback.
 * - **D — definitions, sampling gates and display/statistics thresholds**: they decide WHICH samples are looked at or
 *   how much data a statistic needs; a value that does not fit an engine yields «no data», never a false finding.
 *
 * | Constant | Value | Class | Where it lives now / source |
 * |---|---|---|---|
 * | Thresholds.WARM_COOLANT_C | 80 °C | D | warm gate; wax thermostats open 82–95 °C. Calibrate: plateau norm [NormKey.COOLANT_PLATEAU] must stay above it |
 * | Thresholds.RUNNING_RPM | 400 rpm | D | starter cranking runs at ~100–300 rpm, idle ≥ 500 rpm |
 * | Thresholds.IDLE_MAX_RPM | 1 100 rpm | D + 3 | idle sampling gate; also the generic upper bound of [NormKey.IDLE_RPM] |
 * | Thresholds.STATE_MAX_AGE_MS / COOLANT_MAX_AGE_MS | 10 s / 60 s | D | K-Line cycle ~1.7 s |
 * | Thresholds.OVERHEAT_WARN_C | 105 °C | 2 | safety limit; 50/50 glycol boils at ~129 °C under a 15 psi (~1.0 bar) cap (Motorcraft chart) |
 * | Thresholds.OVERHEAT_CRIT_C | 110 °C | 2 | ≤ 19 °C margin to the ~129 °C boiling point at the cap; local boiling at the head starts earlier |
 * | Thresholds.OVERHEAT_MIN_MS | 30 s | D | ignore single outliers |
 * | Thresholds.VOLTAGE_LOW_V | 13.2 V | 3 | [NormKey.CHARGING_IDLE_V] / [NormKey.CHARGING_DRIVING_V] generic low |
 * | Thresholds.VOLTAGE_HIGH_V | 14.8 V | 3 | same norms, generic high |
 * | Thresholds.VOLTAGE_LOW_MIN_MS / HIGH_MIN_MS | 60 s / 10 s | D | digest statistics only |
 * | Thresholds.LTFT_LIMIT_PCT | ±10 % | 3 | [NormKey.LTFT_B1] / [NormKey.LTFT_B2] generic |
 * | Thresholds.TOTAL_TRIM_CRIT_PCT | ±25 % | 3 | [NormKey.TOTAL_TRIM] generic (ECU-specific P0171 limit) |
 * | Thresholds.BANK_ASYMMETRY_PCT | 8 % | 3 | [NormKey.BANK_ASYMMETRY] generic |
 * | Thresholds.MIN_TRIM_SAMPLES / MAF_IDLE_MIN_SAMPLES | 10 / 5 | D | sample-size gates |
 * | Thresholds.MAF_IDLE_MIN/MAX_G_PER_L | 0.9–1.3 g/s·L | 3 | [NormKey.IDLE_MAF] generic, petrol only |
 * | Thresholds.DTC_REPEAT_WINDOW_MS / DTC_SAME_EVENT_MS | 30 d / 6 h | D | event grouping |
 * | Thresholds.COLD_START_MAX_C | 30 °C | D | cold-start gate |
 * | Thresholds.WARMUP_TREND_RATIO | 1.3 | 3 (relative) | compares the car with its own earlier warm-ups; [NormKey.WARMUP_MIN] is learned |
 * | Thresholds.WARMUP_MIN_STARTS_PER_HALF | 3 | D | sample-size gate |
 * | Thresholds.LIVE_NOT_CHARGING_V | 12.0 V | 2 | below the open-circuit voltage of a 25 % charged battery (12.06 V, BCI/BMW table) |
 * | Thresholds.LIVE_OVERCHARGE_V | 15.2 V | 2 | above the regen-braking peaks of smart alternators (~15.0 V, Sterling Power) |
 * | Thresholds.LIVE_CHARGING_RPM / CONFIRM / HYSTERESIS / COOLDOWN | 500 rpm, 5 s, 3 °C, 0.3 V, 10 min | D | alert mechanics |
 * | Thresholds.STALL_* | 10 s, 11 V, 2, 4 | D | event detection/counting |
 * | Thresholds.HEALTH_BATTERY_LOW_V | 12.2 V | 2 | ≈ 50 % state of charge (12.24 V) |
 * | Thresholds.HEALTH_BATTERY_FAULT_V | 11.8 V | 2 | below 0 % (11.89 V) |
 * | Thresholds.HEALTH_O2_MIN_SWING_V | 0.3 V | 3 | [NormKey.O2_SWING] generic, narrowband sensors only |
 * | Thresholds.HEALTH_IDLE_MIN_RPM | 500 rpm | 3 | [NormKey.IDLE_RPM] generic low |
 * | Thresholds.HEALTH_IDLE_MAX_SWING_RPM | 200 rpm | 3 | [NormKey.IDLE_RPM_SWING] generic |
 * | Thresholds.HEALTH_LIVE_MS | 15 s | D | length of the live sample |
 * | StyleThresholds.G | 9.80665 | 1 | standard gravity (CGPM 1901) |
 * | StyleThresholds.HARSH_ACCEL/BRAKE_MS2 | 0.30 g | 3 (done) | per-car threshold from the accelerometer calibration (source shown), [NormKey.HARSH_ACCEL] |
 * | StyleThresholds.SEVERE_*, windows, gaps, points/caps | — | D | Geotab definitions, score scale |
 * | StyleThresholds.HIGH_RPM | 3 500 rpm | 3 (open) | style score statistic, still generic: redline is not known from OBD |
 * | TripCalculator.PETROL_AFR / DENSITY | 14.7 / 745 g/L | 1 | stoichiometry and EN 228 density; used only for petrol (fuel type gate) |
 * | MaintenanceTemplate intervals | km / months | 3 | generic template, marked as such in the UI; the owner's manual wins (per-item editing) |
 * | Maintenance SOON_FRACTION / SOON_MIN_DAYS | 10 % / 14 d | D | reminder lead time |
 * | EngineMapThresholds.*, StatsTrends.*, AmbientThresholds.*, Trends.*, FuelLog.* | — | D | display/statistics thresholds |
 * | ObdCompatibility years | 1996 / 2001 / 2004 | 1 | US OBD-II / EU 98/69/EC dates |
 * | LiveAlertConfig.validRange | — | D | typo guard for user input |
 *
 * Sources: Motorcraft antifreeze chart (50/50 boils at 129 °C under a 15 psi cap,
 * https://motorcraft.com/content/dam/ford-motorcraft/en_us/motorcraft_global/products/lubricants/quick_reference_charts/ethylene%20glycol.pdf),
 * MACS «The pressure is on» (+3 °F per psi, https://macsmobileairclimate.org/2021/04/19/the-pressure-is-on);
 * lead-acid open-circuit voltage vs state of charge 12.65/12.45/12.24/12.06/11.89 V = 100/75/50/25/0 % (BMW ST051
 * «Battery Basics», https://ia600902.us.archive.org/26/items/BMWTechnicalTrainingDocuments/ST051%20Body%20Electronics%20I/07_Battery%20Basics.pdf;
 * Discover Battery, https://discoverbattery.com/support/learning-center/learning-center-articles/how-can-i-measure-my-batterys-state-of-charge);
 * regulator set points 14.2 V with −10 mV/K (Bosch B3/B5, https://bosch-motorsport.com/content/downloads/Raceparts/en-GB/189035915190427275.html),
 * 14.25/14.55 V (Bosch CR719, https://bosch-semiconductors.com/system-ics/powertrain/cr719), conventional 13.8–14.4 V and
 * smart alternators 12.3–15 V (TecAlliance TSB-VSA-VES-102025-01, https://digital-assets.tecalliance.services/pdf/9738577df8b241f4e4b5b192ff777f231d1cd369.pdf),
 * smart 12.5–15 V (Victron Orion-Tr manual, https://www.victronenergy.com/media/pg/Orion-Tr_Smart_DC-DC_Charger_-_Non-Isolated/en/automatic-engine-shutdown-detection.html),
 * regen swing 12.4–15.0 V (Sterling Power, https://nohma.com/content/uploads/2023/01/Sterling_Power_Regenerative_Braking_Info_ENG.pdf);
 * narrowband O2 0.1–0.9 V switching (Fluke, https://fluke.com/en-us/learn/blog/digital-multimeters/how-to-monitor-oxygen-sensor-voltage-with-a-multimeter);
 * PID 1C/51/14–1B/24–2B/34–3B/70 meanings: SAE J1979 as tabulated in https://en.wikipedia.org/wiki/OBD-II_PIDs;
 * robust baseline: Leys C. et al. (2013) J. Exp. Soc. Psychol. 49(4):764–766, https://doi.org/10.1016/j.jesp.2013.03.013.
 */
enum class NormSource(val priority: Int) {
    /** Entered by the user on «Car norms»: always wins. */
    USER(5),
    /** From a reference (owner's manual, data pack); no reference norms ship yet. */
    REFERENCE(4),
    /** Learned from the car's own warm sessions ([BaselineLearner]). */
    BASELINE(3),
    /** Reported by the ECU itself (traits: fuel type, O2 sensor type, boost PID, OBD standard). */
    ECU(2),
    /** The former global constant: a flagged fallback, findings capped at WARNING. */
    GENERIC(1),
}

enum class NormUnit { CELSIUS, RPM, GRAMS_PER_S, VOLTS, PERCENT, MINUTES, G }

/** Which system a norm belongs to: findings and DTCs of the system stop its baseline from learning. */
enum class NormSystem { COOLING, IDLE, AIR, CHARGING, FUEL_TRIM, O2, DRIVING }

/**
 * One engine-specific (class 3) parameter. [minDelta] = the smallest deviation from the own baseline that counts,
 * absolute in [unit] or, with [relative], a share of the median; a baseline band is median ± max(k·MAD, minDelta).
 * [learnable] = the session digests carry a per-session value for it.
 */
enum class NormKey(val unit: NormUnit, val system: NormSystem, val minDelta: Double, val relative: Boolean = false, val learnable: Boolean = true) {
    /**
     * Coolant plateau with the thermostat open (median of warm samples ≥ [Thresholds.WARM_COOLANT_C] per session).
     * minDelta 5 °C: thermostat opening temperature tolerance is ±2–3 °C and PID 05 has 1 °C resolution, so a shift
     * over 5 °C is more than sensor and part tolerance. Calibrate: the plateau of a long highway recording.
     */
    COOLANT_PLATEAU(NormUnit.CELSIUS, NormSystem.COOLING, 5.0),
    /**
     * Warm idle speed (session median, speed 0). minDelta 100 rpm: workshop manuals give idle as nominal ± 50–100 rpm and
     * electrical/A/C load raises idle by that much. Calibrate: warm idle with consumers off.
     */
    IDLE_RPM(NormUnit.RPM, NormSystem.IDLE, 100.0),
    /** Idle hunting within the 15 s health-check sample; not learned (sessions do not keep it). */
    IDLE_RPM_SWING(NormUnit.RPM, NormSystem.IDLE, 0.0, learnable = false),
    /**
     * Warm idle air flow, g/s (absolute, so it works without the displacement). minDelta 20 % of the median: MAF
     * accuracy is a few percent and A/C or alternator load changes idle air by 15–25 %. Calibrate: warm idle, A/C off.
     */
    IDLE_MAF(NormUnit.GRAMS_PER_S, NormSystem.AIR, 0.20, relative = true),
    /**
     * Charging voltage at warm idle (session median, engine running, speed 0). minDelta 0.3 V: the regulator set point
     * moves −10 mV/K (Bosch), i.e. 0.3 V over a 30 K under-bonnet temperature swing.
     */
    CHARGING_IDLE_V(NormUnit.VOLTS, NormSystem.CHARGING, 0.3),
    /** Charging voltage while driving (session median, speed > 0); smart alternators sit at 12.4–12.8 V here. */
    CHARGING_DRIVING_V(NormUnit.VOLTS, NormSystem.CHARGING, 0.3),
    /** Long-term trim bank 1 on a warm engine (session median). minDelta 5 %: LTFT normally sits within ±5–10 %, PID 07 step 0.78 %. */
    LTFT_B1(NormUnit.PERCENT, NormSystem.FUEL_TRIM, 5.0),
    LTFT_B2(NormUnit.PERCENT, NormSystem.FUEL_TRIM, 5.0),
    /** STFT + LTFT per bank (session means, worst bank). minDelta 10 %: STFT alone swings ±5–10 % in closed loop. */
    TOTAL_TRIM(NormUnit.PERCENT, NormSystem.FUEL_TRIM, 10.0),
    /** |LTFT1 − LTFT2| of a V engine (session means). minDelta 4 %: about five PID 07/09 steps. */
    BANK_ASYMMETRY(NormUnit.PERCENT, NormSystem.FUEL_TRIM, 4.0),
    /** Narrowband O2 B1S1 swing at warm idle (health check only; wideband sensors are not judged). */
    O2_SWING(NormUnit.VOLTS, NormSystem.O2, 0.0, learnable = false),
    /** Warm-up time from a cold start to [Thresholds.WARM_COOLANT_C]; relative 30 % = [Thresholds.WARMUP_TREND_RATIO]. */
    WARMUP_MIN(NormUnit.MINUTES, NormSystem.COOLING, 0.30, relative = true),
    /** Harsh accel/brake threshold, linked from the accelerometer calibration (StyleThresholds / AccelCalibration). */
    HARSH_ACCEL(NormUnit.G, NormSystem.DRIVING, 0.0, learnable = false),
}

/**
 * The norm of one parameter for one car: allowed band [low]..[high] (either side may be open), [center] = baseline
 * median, [mad] = scaled MAD (b = 1.4826), [sampleCount] = sessions behind a baseline, [confidence] 0..1.
 */
data class Norm(
    val key: NormKey,
    val low: Double?,
    val high: Double?,
    val source: NormSource,
    val center: Double? = null,
    val mad: Double? = null,
    val sampleCount: Int = 0,
    val confidence: Double = if (source == NormSource.GENERIC) 0.0 else 1.0,
    val updatedAtMs: Long = 0,
) {
    val isGeneric: Boolean get() = source == NormSource.GENERIC
    fun below(v: Double) = low != null && v < low
    fun above(v: Double) = high != null && v > high
    fun outside(v: Double) = below(v) || above(v)
}

/** Fuel from PID 51 (SAE J1979 fuel type coding) or the profile. */
enum class FuelKind { PETROL, DIESEL, GAS, ELECTRIC, HYBRID, OTHER }

/** Upstream oxygen sensor: narrowband (PIDs 14–1B, 0.1–0.9 V) or wideband (24–2B / 34–3B, lambda). */
enum class O2Kind { NARROWBAND, WIDEBAND }

data class Trait<T>(val value: T, val source: NormSource, val updatedAtMs: Long = 0)

/** What the ECU (or the profile) says about the engine; rules that do not apply to these traits are disabled. */
data class EngineTraits(
    val fuel: Trait<FuelKind>? = null,
    val o2: Trait<O2Kind>? = null,
    val forcedInduction: Trait<Boolean>? = null,
    /** PID 1C raw value (1 = OBD-II CARB, 6 = EOBD, …). */
    val obdStandard: Trait<Int>? = null,
    /** PID 10 supported: a MAF sensor exists. */
    val hasMaf: Trait<Boolean>? = null,
) {
    val isDiesel: Boolean get() = fuel?.value == FuelKind.DIESEL
    val isWideband: Boolean get() = o2?.value == O2Kind.WIDEBAND

    /** Profile fuel (USER) wins over the ECU, ECU over nothing. */
    fun withProfile(car: CarProfile?): EngineTraits {
        val f = car?.fuelType?.let(::fuelOf) ?: return this
        return copy(fuel = Trait(f, NormSource.USER))
    }

    /** Fills gaps of this from [other] (stored ECU traits under detected-now ones). */
    fun orElse(other: EngineTraits) = EngineTraits(
        fuel ?: other.fuel, o2 ?: other.o2, forcedInduction ?: other.forcedInduction, obdStandard ?: other.obdStandard, hasMaf ?: other.hasMaf,
    )

    companion object {
        fun fuelOf(t: FuelType): FuelKind = when (t) {
            FuelType.PETROL -> FuelKind.PETROL
            FuelType.DIESEL -> FuelKind.DIESEL
            FuelType.LPG, FuelType.CNG -> FuelKind.GAS
            FuelType.HYBRID -> FuelKind.HYBRID
            FuelType.ELECTRIC -> FuelKind.ELECTRIC
            FuelType.OTHER -> FuelKind.OTHER
        }
    }
}

/** Traits from what the ECU reports. Pure; the data layer reads the PIDs (read services only) and stores the result. */
object EngineTraitDetector {
    /** Margin of MAP over barometric pressure that means boost: > 5× a MAP sensor's ±1.5 kPa accuracy. */
    const val BOOST_MARGIN_KPA = 10.0
    /** Boost must be seen in this many samples (one spike is not a turbo). */
    const val BOOST_MIN_SAMPLES = 3

    /** PID 51 byte (SAE J1979 table): 1 petrol, 2/3 alcohols, 4 diesel, 5–7 gas, 8 electric, 9–23 bi-fuel/hybrid kinds. */
    fun fuelFromPid51(code: Int): FuelKind? = when (code) {
        1, 2, 3, 9, 10, 11 -> FuelKind.PETROL
        4, 23 -> FuelKind.DIESEL
        5, 6, 7, 12, 13, 14 -> FuelKind.GAS
        8, 15 -> FuelKind.ELECTRIC
        19 -> FuelKind.HYBRID
        16, 17, 18, 20, 21, 22 -> FuelKind.HYBRID
        else -> null
    }

    /** From the supported-PID bitmap (mode 01): O2 type of the upstream sensors, boost PID 70, MAF PID 10. */
    fun fromSupportedPids(pids: Set<Int>, atMs: Long = 0): EngineTraits {
        val wide = pids.any { it in 0x24..0x2B || it in 0x34..0x3B }
        val narrow = pids.any { it in 0x14..0x1B }
        val o2 = when {
            wide -> O2Kind.WIDEBAND
            narrow -> O2Kind.NARROWBAND
            else -> null
        }
        return EngineTraits(
            o2 = o2?.let { Trait(it, NormSource.ECU, atMs) },
            forcedInduction = if (0x70 in pids) Trait(true, NormSource.ECU, atMs) else null,
            hasMaf = if (pids.isEmpty()) null else Trait(0x10 in pids, NormSource.ECU, atMs),
        )
    }

    /** Recorded sensor ids («01.24» …) tell the O2 type of imported recordings too. */
    fun fromRecordedSensors(ids: Set<String>, atMs: Long = 0): EngineTraits {
        val pids = ids.filter { it.startsWith("01.") }.mapNotNull { it.drop(3).toIntOrNull(16) }.toSet()
        val t = fromSupportedPids(pids, atMs)
        // A sensor absent from recordings may just not have been polled: only positive facts count here.
        return t.copy(hasMaf = t.hasMaf?.takeIf { it.value }, forcedInduction = t.forcedInduction)
    }

    /** MAP above barometric pressure by [BOOST_MARGIN_KPA] in ≥ [BOOST_MIN_SAMPLES] samples = forced induction. */
    fun forcedFromBoost(mapKpa: List<Double>, baroKpa: Double?): Boolean? {
        if (baroKpa == null || mapKpa.isEmpty()) return null
        return if (mapKpa.count { it > baroKpa + BOOST_MARGIN_KPA } >= BOOST_MIN_SAMPLES) true else null
    }
}

/**
 * The norms known for one car (stored rows: USER, REFERENCE, BASELINE, ECU) plus the generic fallbacks computed from
 * the profile and traits. [effective] picks the highest-priority source; BASELINE rows count only from
 * [BaselineLearner.MIN_SESSIONS] sessions.
 */
data class CarNorms(val stored: List<Norm> = emptyList(), val car: CarProfile? = null, val traits: EngineTraits = EngineTraits()) {
    fun effective(key: NormKey): Norm? =
        stored.filter { it.key == key && (it.source != NormSource.BASELINE || it.sampleCount >= BaselineLearner.MIN_SESSIONS) }
            .maxByOrNull { it.source.priority }
            ?: generic(key)

    fun baseline(key: NormKey): Norm? = stored.firstOrNull { it.key == key && it.source == NormSource.BASELINE }

    fun generic(key: NormKey): Norm? = GenericNorms.of(key, car, traits)

    companion object {
        val EMPTY = CarNorms()
    }
}

/** The former global constants as flagged fallbacks; null where no generic number fits the engine. */
object GenericNorms {
    fun of(key: NormKey, car: CarProfile?, traits: EngineTraits): Norm? {
        fun g(low: Double?, high: Double?) = Norm(key, low, high, NormSource.GENERIC)
        val diesel = traits.isDiesel || car?.fuelType == FuelType.DIESEL
        return when (key) {
            NormKey.COOLANT_PLATEAU -> g(Thresholds.WARM_COOLANT_C, Thresholds.OVERHEAT_WARN_C)
            NormKey.IDLE_RPM -> g(Thresholds.HEALTH_IDLE_MIN_RPM, Thresholds.IDLE_MAX_RPM)
            NormKey.IDLE_RPM_SWING -> g(null, Thresholds.HEALTH_IDLE_MAX_SWING_RPM)
            // 0.9–1.3 g/s·L is a throttled petrol engine; an unthrottled diesel idles with far more air.
            NormKey.IDLE_MAF -> car?.engineVolumeL?.takeIf { it > 0 && !diesel }?.let { v -> g(Thresholds.MAF_IDLE_MIN_G_PER_L * v, Thresholds.MAF_IDLE_MAX_G_PER_L * v) }
            NormKey.CHARGING_IDLE_V, NormKey.CHARGING_DRIVING_V -> g(Thresholds.VOLTAGE_LOW_V, Thresholds.VOLTAGE_HIGH_V)
            NormKey.LTFT_B1, NormKey.LTFT_B2 -> g(-Thresholds.LTFT_LIMIT_PCT, Thresholds.LTFT_LIMIT_PCT)
            NormKey.TOTAL_TRIM -> g(-Thresholds.TOTAL_TRIM_CRIT_PCT, Thresholds.TOTAL_TRIM_CRIT_PCT)
            NormKey.BANK_ASYMMETRY -> g(null, Thresholds.BANK_ASYMMETRY_PCT)
            NormKey.O2_SWING -> if (traits.isWideband) null else g(Thresholds.HEALTH_O2_MIN_SWING_V, null)
            NormKey.WARMUP_MIN -> null
            NormKey.HARSH_ACCEL -> g(null, StyleThresholds.HARSH_ACCEL_MS2 / StyleThresholds.G)
        }
    }
}

package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.analytics.FindingKey as K

/**
 * Typed «compared with» text of a [Norm]: «LTFT bank 1: −5.0…+5.0 % (own baseline, recordings: 12)». The UI renders
 * it from resources (:core:analyticstext), the AI in English via [FindingEnglish].
 */
object NormText {
    fun name(key: NormKey): FindingText = FindingText(
        when (key) {
            NormKey.COOLANT_PLATEAU -> K.NAME_COOLANT_PLATEAU
            NormKey.IDLE_RPM -> K.NAME_IDLE_RPM
            NormKey.IDLE_RPM_SWING -> K.NAME_IDLE_RPM_SWING
            NormKey.IDLE_MAF -> K.NAME_IDLE_MAF
            NormKey.CHARGING_IDLE_V -> K.NAME_CHARGING_IDLE_V
            NormKey.CHARGING_DRIVING_V -> K.NAME_CHARGING_DRIVING_V
            NormKey.LTFT_B1 -> K.NAME_LTFT_B1
            NormKey.LTFT_B2 -> K.NAME_LTFT_B2
            NormKey.TOTAL_TRIM -> K.NAME_TOTAL_TRIM
            NormKey.BANK_ASYMMETRY -> K.NAME_BANK_ASYMMETRY
            NormKey.O2_SWING -> K.NAME_O2_SWING
            NormKey.WARMUP_MIN -> K.NAME_WARMUP_MIN
            NormKey.HARSH_ACCEL -> K.NAME_HARSH_ACCEL
        },
    )

    fun decimals(unit: NormUnit): Int = when (unit) {
        NormUnit.CELSIUS, NormUnit.RPM, NormUnit.MINUTES -> 0
        NormUnit.GRAMS_PER_S, NormUnit.VOLTS, NormUnit.PERCENT -> 1
        NormUnit.G -> 2
    }

    /** A value with its unit («13.6 V», «+4.5 %»). */
    fun value(unit: NormUnit, v: Double): FindingText = withUnit(unit, FindingText(K.VALUE_PLAIN, num(unit, v)))

    /** The band with its unit: «a…b unit», «≥ a unit», «≤ b unit». */
    fun range(n: Norm): FindingText {
        val inner = when {
            n.low != null && n.high != null -> FindingText(K.NORM_RANGE, num(n.key.unit, n.low), num(n.key.unit, n.high))
            n.low != null -> FindingText(K.NORM_AT_LEAST, num(n.key.unit, n.low))
            n.high != null -> FindingText(K.NORM_AT_MOST, num(n.key.unit, n.high))
            else -> FindingText(K.VALUE_PLAIN, FindingArg.Raw("—"))
        }
        return withUnit(n.key.unit, inner)
    }

    fun source(n: Norm): FindingText = when (n.source) {
        NormSource.GENERIC -> FindingText(K.NORM_SOURCE_GENERIC)
        NormSource.BASELINE -> FindingText(K.NORM_SOURCE_BASELINE, FindingArg.Count(n.sampleCount))
        NormSource.USER -> FindingText(K.NORM_SOURCE_USER)
        NormSource.REFERENCE -> FindingText(K.NORM_SOURCE_REFERENCE)
        NormSource.ECU -> FindingText(K.NORM_SOURCE_ECU)
    }

    /** «name: range (source)». */
    fun comparedWith(n: Norm): FindingText = FindingText(K.NORM_VALUE, FindingArg.Text(name(n.key)), FindingArg.Text(range(n)), FindingArg.Text(source(n)))

    fun evidence(n: Norm): Evidence = Evidence(FindingText(K.EVIDENCE_COMPARED_WITH), comparedWith(n))

    private fun num(unit: NormUnit, v: Double) = FindingArg.Num(v, decimals(unit), signed = unit == NormUnit.PERCENT)

    private fun withUnit(unit: NormUnit, inner: FindingText): FindingText {
        val key = when (unit) {
            NormUnit.CELSIUS -> K.VALUE_CELSIUS
            NormUnit.RPM -> K.VALUE_RPM
            NormUnit.GRAMS_PER_S -> K.VALUE_GRAMS_PER_S
            NormUnit.VOLTS -> K.VALUE_VOLTS
            NormUnit.PERCENT -> K.VALUE_PERCENT
            NormUnit.MINUTES -> K.VALUE_MINUTES
            NormUnit.G -> K.VALUE_G
        }
        return FindingText(key, FindingArg.Text(inner))
    }

    /** English line for the AI and logs. */
    fun english(n: Norm): String = FindingEnglish.render(comparedWith(n))
}

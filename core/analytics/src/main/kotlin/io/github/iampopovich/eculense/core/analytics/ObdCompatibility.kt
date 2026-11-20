package io.github.iampopovich.eculense.core.analytics

import io.github.iampopovich.eculense.core.model.FuelType

/** Which mandate the phone's country follows: EU/EEA (EOBD) or US/Canada (OBD-II). */
enum class ObdRule { EU, US }

/** C12: the car may predate mandatory OBD-II/EOBD. [lateAdoption] = RU/BY/KZ/UA adopted EOBD later than the EU. */
data class CompatibilityHint(val rule: ObdRule, val lateAdoption: Boolean)

/**
 * C12: a non-blocking hint by model year. EOBD became mandatory for new petrol cars from 2001 and for diesel from
 * 2004 (EU/EEA); OBD-II from model year 1996 in the US (Canada follows). RU/BY/KZ/UA get the EU years plus a note
 * that the mandate came later there, so older cars may lack it too. Other countries: no hint (no single rule).
 */
object ObdCompatibility {
    const val EU_PETROL_FROM = 2001
    const val EU_DIESEL_FROM = 2004
    const val US_FROM = 1996

    private val EU_EEA = setOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT",
        "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE", "IS", "LI", "NO",
    )
    private val LATE_EU = setOf("RU", "BY", "KZ", "UA")
    private val US_RULE = setOf("US", "CA")

    /** [country] = ISO 3166 alpha-2 of the phone locale. */
    fun hint(year: Int?, fuel: FuelType?, country: String): CompatibilityHint? {
        if (year == null || fuel == FuelType.ELECTRIC) return null
        val c = country.uppercase()
        return when (c) {
            in US_RULE -> if (year < US_FROM) CompatibilityHint(ObdRule.US, lateAdoption = false) else null
            in EU_EEA, in LATE_EU -> {
                val from = if (fuel == FuelType.DIESEL) EU_DIESEL_FROM else EU_PETROL_FROM
                if (year < from) CompatibilityHint(ObdRule.EU, lateAdoption = c in LATE_EU) else null
            }
            else -> null
        }
    }
}

package io.github.iampopovich.eculense.core.dtcdb

/**
 * Code description that takes the active car's make into account.
 * Generic codes: OBDex (if the pack is downloaded) → bundled CSV. Manufacturer codes: only the car make's database; otherwise an honest
 * «Manufacturer-specific code, description unknown»; another make's description is worse than none.
 */
class DtcCatalog(
    private val generic: GenericDtcSource?,
    private val builtin: (String) -> String?,
    private val oem: OemDtcSource?,
    private val oemManufacturers: Set<String> = emptySet(),
) {
    fun describe(rawCode: String, make: String?): DtcInfo {
        val code = rawCode.trim().uppercase()
        if (DtcCodes.isManufacturerSpecific(code)) {
            val m = OemBrands.manufacturer(make, oemManufacturers)
            val text = m?.let { oem?.lookup(code, it) }
            return if (text != null) DtcInfo(code, text, DtcSource.OEM, manufacturer = m) else DtcInfo(code, null, DtcSource.OEM_UNKNOWN)
        }
        generic?.lookup(code)?.let { return it }
        builtin(code)?.let { return DtcInfo(code, it, DtcSource.BUILTIN) }
        return DtcInfo(code, null, DtcSource.UNKNOWN)
    }
}

private val csvLine = Regex("^\"([A-Z0-9]{5})\",\"(.*)\"$")

/** Bundled `assets/dtc_codes.csv` (lennykean/OBDII.DTC, MIT): generic ranges only. */
fun parseDtcCsv(lines: Sequence<String>): Map<String, String> =
    lines.mapNotNull { csvLine.find(it.trim())?.destructured }.associate { (code, text) -> code to text }

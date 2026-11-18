package io.github.iampopovich.eculense.core.dtcdb

/**
 * Расшифровка кода с учётом марки активной машины.
 * Общие коды: OBDex (если пакет скачан) → встроенный CSV. Заводские: только база марки машины; иначе честное
 * «Заводской код производителя, расшифровка неизвестна» — чужая расшифровка хуже никакой.
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

/** Встроенный `assets/dtc_codes.csv` (lennykean/OBDII.DTC, MIT): только общие диапазоны. */
fun parseDtcCsv(lines: Sequence<String>): Map<String, String> =
    lines.mapNotNull { csvLine.find(it.trim())?.destructured }.associate { (code, text) -> code to text }

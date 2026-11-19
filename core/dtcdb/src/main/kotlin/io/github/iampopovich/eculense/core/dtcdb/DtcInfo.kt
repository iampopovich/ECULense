package io.github.iampopovich.eculense.core.dtcdb

/** Where the description comes from: this determines how it's shown and whether it can be trusted for this car. */
enum class DtcSource {
    /** OBDex: title, description, causes, symptoms. */
    OBDEX,
    /** Bundled CSV (title only): the OBDex pack is not downloaded. */
    BUILTIN,
    /** Manufacturer description for the active car's make (Wal33D). */
    OEM,
    /** Manufacturer code with no description for the car's make; another make's description is not shown. */
    OEM_UNKNOWN,
    /** Generic code without a description. */
    UNKNOWN,
}

data class DtcInfo(
    val code: String,
    /** Code title (English); null means unknown. */
    val title: String?,
    val source: DtcSource,
    val description: String? = null,
    val causes: List<String> = emptyList(),
    val symptoms: List<String> = emptyList(),
    /** Make in the manufacturer code database under which the description was found. */
    val manufacturer: String? = null,
) {
    /** List line: the title or an honest «не знаем». */
    val text: String
        get() = title ?: when (source) {
            DtcSource.OEM_UNKNOWN -> OEM_UNKNOWN_TEXT
            else -> "Расшифровка не найдена"
        }

    companion object {
        const val OEM_UNKNOWN_TEXT = "Заводской код производителя, расшифровка неизвестна"
    }
}

object DtcCodes {
    private val FORMAT = Regex("^[PBCU][0-3][0-9A-F]{3}$")

    fun isValid(code: String): Boolean = FORMAT.matches(code)

    /**
     * SAE J2012: generic are P0, P2, P34–P39, B0/B3, C0/C3, U0/U3; manufacturer are P1, P30–P33, B1/B2, C1/C2, U1/U2.
     * Manufacturer code numbers overlap between makes: P1847 on GM is a transmission solenoid, on Kia something else.
     */
    fun isManufacturerSpecific(code: String): Boolean {
        if (!isValid(code)) return false
        val d1 = code[1]
        return when (code[0]) {
            'P' -> d1 == '1' || (d1 == '3' && code[2] in '0'..'3')
            else -> d1 == '1' || d1 == '2'
        }
    }
}

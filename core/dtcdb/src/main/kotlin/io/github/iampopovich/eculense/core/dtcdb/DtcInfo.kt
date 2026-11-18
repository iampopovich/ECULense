package io.github.iampopovich.eculense.core.dtcdb

/** Откуда расшифровка: от этого зависит, как её показывать и можно ли ей доверять для этой машины. */
enum class DtcSource {
    /** OBDex: заголовок, описание, причины, симптомы. */
    OBDEX,
    /** Встроенный CSV (только заголовок) — пакет OBDex не скачан. */
    BUILTIN,
    /** Заводская расшифровка для марки активной машины (Wal33D). */
    OEM,
    /** Заводской код, для марки машины расшифровки нет — чужую (другой марки) не показываем. */
    OEM_UNKNOWN,
    /** Общий код без расшифровки. */
    UNKNOWN,
}

data class DtcInfo(
    val code: String,
    /** Название кода (англ.); null — неизвестно. */
    val title: String?,
    val source: DtcSource,
    val description: String? = null,
    val causes: List<String> = emptyList(),
    val symptoms: List<String> = emptyList(),
    /** Марка в базе заводских кодов, по которой найдена расшифровка. */
    val manufacturer: String? = null,
) {
    /** Строка для списка: название или честное «не знаем». */
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
     * SAE J2012: общие — P0, P2, P34–P39, B0/B3, C0/C3, U0/U3; заводские — P1, P30–P33, B1/B2, C1/C2, U1/U2.
     * Номера заводских кодов пересекаются между марками: P1847 у GM — соленоид КПП, у Kia — что-то своё.
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

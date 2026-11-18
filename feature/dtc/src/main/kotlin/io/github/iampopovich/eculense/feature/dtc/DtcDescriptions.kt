package io.github.iampopovich.eculense.feature.dtc

import android.content.Context

/**
 * Описания кодов из assets/dtc_codes.csv: только общие SAE/ISO-коды (P0, P2, P34–P39, B0, B3, C0, C3, U0, U3),
 * сконвертированные из lennykean/OBDII.DTC (MIT, см. dtc_codes.LICENSE) скриптом tools/obdii_dtc_to_csv.py.
 * Заводские диапазоны не включены: номера там пересекаются между марками, описание ввело бы в заблуждение.
 */
internal fun interface DtcDescriptions {
    fun describe(code: String): String?
}

private val csvLine = Regex("^\"([A-Z0-9]{5})\",\"(.*)\"$")

internal fun parseDtcCsv(lines: Sequence<String>): Map<String, String> =
    lines.mapNotNull { csvLine.find(it.trim())?.destructured }.associate { (code, text) -> code to text }

internal class AssetDtcDescriptions(private val context: Context) : DtcDescriptions {
    private val table: Map<String, String> by lazy {
        context.assets.open("dtc_codes.csv").bufferedReader().useLines { parseDtcCsv(it) }
    }

    override fun describe(code: String): String? = table[code]
}

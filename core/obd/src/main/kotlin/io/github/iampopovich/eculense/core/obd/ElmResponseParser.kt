package io.github.iampopovich.eculense.core.obd

internal sealed interface ElmResult {
    data class Data(val lines: List<String>) : ElmResult
    data object NoData : ElmResult
    data class Error(val message: String) : ElmResult
}

internal object ElmResponseParser {
    private val noise = setOf("SEARCHING...", "OK")
    private val errorMarkers = listOf(
        "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS BUSY", "DATA ERROR",
        "BUFFER FULL", "FB ERROR", "LV RESET", "STOPPED", "ERR",
    )

    fun parse(raw: String): ElmResult {
        val lines = raw.split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it in noise || it.startsWith("BUS INIT") }
        if (lines.any { it == "NO DATA" }) return ElmResult.NoData
        lines.firstOrNull { line -> line == "?" || errorMarkers.any { line.contains(it) } }
            ?.let { return ElmResult.Error(it) }
        return ElmResult.Data(lines.map { it.replace(" ", "").uppercase() })
    }
}

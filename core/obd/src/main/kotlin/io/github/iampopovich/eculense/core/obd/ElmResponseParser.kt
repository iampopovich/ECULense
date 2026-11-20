package io.github.iampopovich.eculense.core.obd

internal sealed interface ElmResult {
    data class Data(val lines: List<String>) : ElmResult
    data object NoData : ElmResult
    data class Error(val message: String) : ElmResult

    /** "BUS INIT: ...ERROR": the slow/fast K-Line init failed (ignition off, wrong protocol). */
    data object BusInitFailed : ElmResult
}

internal object ElmResponseParser {
    private val noise = setOf("SEARCHING...", "OK")
    private val errorMarkers = listOf(
        "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS BUSY", "DATA ERROR",
        "BUFFER FULL", "FB ERROR", "LV RESET", "STOPPED", "ERR",
    )

    /**
     * [command]: the request this reply belongs to; a line equal to it is the adapter's echo, not data.
     * Lines starting with '+' are verbose status lines of some clones.
     */
    fun parse(raw: String, command: String? = null): ElmResult {
        val echo = command?.replace(" ", "")?.uppercase()
        val lines = raw.split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("+") }
            .filterNot { echo != null && it.replace(" ", "").uppercase() == echo }
        if (lines.any { it.startsWith("BUS INIT") && it.contains("ERROR") }) return ElmResult.BusInitFailed
        val meaningful = lines.filterNot { it in noise || it.startsWith("BUS INIT") }
        if (meaningful.any { it == "NO DATA" }) return ElmResult.NoData
        meaningful.firstOrNull { line -> line == "?" || errorMarkers.any { line.contains(it) } }
            ?.let { return ElmResult.Error(it) }
        return ElmResult.Data(meaningful.map { it.replace(" ", "").uppercase() })
    }
}

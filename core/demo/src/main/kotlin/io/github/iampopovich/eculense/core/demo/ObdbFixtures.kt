package io.github.iampopovich.eculense.core.demo

/** One OBDb test case: raw ATH1/ATS0 reply lines and the values OBDb expects (signal id → YAML scalar text). */
data class ObdbTestCase(val response: List<String>, val expected: Map<String, String>)

/** One OBDb `tests/test_cases/<year>/commands/<id>.yaml` file. */
data class ObdbTestCaseFile(val commandId: String, val cases: List<ObdbTestCase>) {
    /** «7E4.7EC.220101|fc=1» → header 7E4. */
    val header: String get() = commandId.substringBefore('.')

    /** «7E4.7EC.220101|fc=1» → request 220101. */
    val request: String get() = commandId.substringBefore('|').substringAfterLast('.')
}

/**
 * R-E (debug builds and tests only): the OBDb files bundled under resources/obdb (CC BY-SA 4.0, see NOTICE.md there)
 * and a tiny reader for the two fields of the test-case YAML we need (`response`, `expected_values`) — no YAML
 * library in the app for this.
 */
object ObdbFixtures {
    const val NIRO_EV = "Kia-Niro-EV"
    const val SORENTO = "Kia-Sorento"
    const val NIRO_EV_YEAR = 2020
    const val SORENTO_YEAR = 2017

    /** Test-case files per repository (resource names; '|' of the OBDb names is '_'). */
    val files: Map<String, List<String>> = mapOf(
        NIRO_EV to listOf("2020/7E4.7EC.220101_fc=1.yaml", "2020/7E4.7EC.220105_fc=1.yaml", "2020/7E2.7EA.2101.yaml"),
        SORENTO to listOf("2017/7E0.7E8.2101_f=-2020.yaml", "2017/7E1.7E9.21A0_f=-2020.yaml", "2017/7C6.7CE.22B002_fc=1.yaml"),
    )

    fun text(path: String): String =
        ObdbFixtures::class.java.getResourceAsStream("/obdb/$path")?.use { it.readBytes().decodeToString() }
            ?: error("missing fixture obdb/$path")

    fun signalset(repo: String): String = text("$repo/default.json")

    fun testCases(repo: String): List<ObdbTestCaseFile> = files.getValue(repo).map { parse(text("$repo/$it")) }

    /** Reads `command_id` and every test case's `expected_values` and `response` (block or single line). */
    fun parse(yaml: String): ObdbTestCaseFile {
        var id = ""
        val cases = mutableListOf<ObdbTestCase>()
        var expected = LinkedHashMap<String, String>()
        var response = mutableListOf<String>()
        var section: String? = null
        var started = false
        fun flush() {
            if (started) cases += ObdbTestCase(response.toList(), expected.toMap())
            expected = LinkedHashMap()
            response = mutableListOf()
        }
        for (raw in yaml.lines()) {
            if (raw.isBlank()) continue
            val indent = raw.length - raw.trimStart().length
            val line = raw.trim()
            when {
                indent == 0 && line.startsWith("command_id:") -> id = line.substringAfter(':').trim().trim('"', '\'')
                indent == 0 && line.startsWith("- ") -> {
                    flush()
                    started = true
                    section = key(line.removePrefix("- "))?.also { (k, v) -> if (k == "response" && v.isNotEmpty() && v != "|-" && v != "|") response += v }?.first
                }
                indent == 0 -> section = null
                indent == 2 && started -> section = key(line)?.also { (k, v) -> if (k == "response" && v.isNotEmpty() && v != "|-" && v != "|") response += v }?.first
                indent >= 4 && started && section == "expected_values" -> key(line)?.let { (k, v) -> expected[k] = v.trim('"', '\'') }
                indent >= 4 && started && section == "response" -> response += line
            }
        }
        flush()
        return ObdbTestCaseFile(id, cases)
    }

    private fun key(line: String): Pair<String, String>? {
        val i = line.indexOf(':')
        if (i <= 0) return null
        return line.substring(0, i).trim() to line.substring(i + 1).trim()
    }
}

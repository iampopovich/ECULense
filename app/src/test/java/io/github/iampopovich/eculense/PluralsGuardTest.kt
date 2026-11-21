package io.github.iampopovich.eculense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Localization guard: a count followed by a noun («%1$d отсчётов», «%1$d samples», «%1$d дн. назад») needs a
 * `<plurals>` resource, because the noun's form depends on the number (RU one/few/many, EN one/other). Heuristic:
 * in every `<string>` of the XML files under `res/values*` folders, an integer placeholder directly followed by a word is an error unless the
 * word is an invariant unit or abbreviation ([INVARIANT_WORDS]) or the string is listed in [ALLOWED_NAMES].
 * The «Label: %d» form (number after a colon) never fails, so it is the escape hatch for counts in labels.
 */
class PluralsGuardTest {
    private val root: File = File("..").canonicalFile
    private val skippedDirs = setOf("build", ".git", ".gradle", ".idea", ".hermes", "build-logic", "spike")

    @Test
    fun `strings with a count followed by a noun use plurals`() {
        assertTrue("repo root not found", File(root, "settings.gradle.kts").isFile)
        val violations = mutableListOf<String>()
        var scanned = 0
        root.walkTopDown()
            .onEnter { it == root || it.name !in skippedDirs }
            .filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") && "/src/main/res/" in it.invariantSeparatorsPath }
            .forEach { file ->
                scanned++
                STRING.findAll(file.readText()).forEach { m ->
                    val name = m.groupValues[1]
                    val word = countedWord(m.groupValues[2])
                    if (word != null && name !in ALLOWED_NAMES) {
                        violations += "${file.relativeTo(root).invariantSeparatorsPath}: $name «$word» in ${m.groupValues[2].take(80)}"
                    }
                }
            }
        assertTrue("no resource files scanned", scanned > 10)
        assertTrue(
            "Count + noun outside <plurals>: use <plurals> + pluralStringResource/getQuantityString, or the «Label: %d» form:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `heuristic flags nouns and accepts units, labels and plurals`() {
        assertEquals("отсчётов", countedWord("Датчик: %1\$d отсчётов"))
        assertEquals("samples", countedWord("%1\$d samples, %2\$s"))
        assertEquals("дн.", countedWord("%1\$d\\u00A0дн. назад"))
        assertEquals(null, countedWord("Записей: %1\$d"))
        assertEquals(null, countedWord("%1\$d км"))
        assertEquals(null, countedWord("%1\$d h %2\$02d min"))
        assertEquals(null, countedWord("попытка %1\$d через %2\$d с…"))
        assertEquals(null, countedWord("%1\$d %%"))
    }

    private companion object {
        /** `<string name="x">value</string>`; `<item>`s of `<plurals>` are not matched. */
        val STRING = Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        val COUNT_THEN_WORD = Regex("%(?:\\d+\\$)?0?\\d*d(?:\\s|\\\\u00A0|\u00A0)+([\\p{L}][\\p{L}./³]*)")

        /** Units and abbreviations that do not change with the number, and connectors that are not the counted noun. */
        val INVARIANT_WORDS = setOf(
            "GB", "ГБ", "MB", "МБ", "KB", "КБ", "min", "мин", "s", "с", "h", "ч", "km", "км", "kg", "кг", "ms", "мс", "rpm",
            "об/мин", "cc", "см³", "cyl", "цил", "L", "л", "g", "mo", "мес",
            "of", "из", "in", "через", "more", "already", "updated", "incomplete", "needed",
        )

        /** Numbers that are not counts: a model year («%2$d год» = «2005 год»). */
        val ALLOWED_NAMES = setOf("datapacks_not_covered", "datapacks_not_covered_gen")

        fun countedWord(value: String): String? = COUNT_THEN_WORD.findAll(value)
            .map { it.groupValues[1] }
            .firstOrNull { it !in INVARIANT_WORDS && it.trimEnd('.') !in INVARIANT_WORDS }
    }
}

package io.github.iampopovich.eculense

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Localization guard: localized strings are not glued together with `+` («stringResource(a) + ": " + x»), because
 * word order and punctuation differ between languages. Use one template with arguments instead (a feature string, or
 * the punctuation templates `common_join_dot`, `common_bullet`, `common_two_lines` in :core:ui). Heuristic per line of
 * main Kotlin sources (comments stripped): `+` right before or right after a `stringResource(...)` /
 * `pluralStringResource(...)` / `getString(...)` call.
 */
class StringConcatGuardTest {
    private val root: File = File("..").canonicalFile
    private val skippedDirs = setOf("build", ".git", ".gradle", ".idea", ".hermes", "build-logic", "spike")

    @Test
    fun `localized strings are not concatenated with plus`() {
        assertTrue("repo root not found", File(root, "settings.gradle.kts").isFile)
        val violations = mutableListOf<String>()
        root.walkTopDown()
            .onEnter { it == root || it.name !in skippedDirs }
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
            .forEach { file ->
                KotlinLexer.stripComments(file.readText()).lines().forEachIndexed { i, line ->
                    if (concatenates(line)) violations += "${file.relativeTo(root).invariantSeparatorsPath}:${i + 1}: ${line.trim()}"
                }
            }
        assertTrue("Concatenated localized strings, use a template with arguments:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun `heuristic catches plus around string lookups only`() {
        assertTrue(concatenates("""Text(stringResource(R.string.a) + ": " + x)"""))
        assertTrue(concatenates("""val s = "• " + stringResource(it)"""))
        assertTrue(concatenates("""context.getString(R.string.a, n) + " · " + b"""))
        assertTrue(concatenates("""stringResource(R.string.a, f(x), g(y)) + tail"""))
        assertFalse(concatenates("""Text(stringResource(R.string.a, x + 1))"""))
        assertFalse(concatenates("""val n = count + 1"""))
    }

    private companion object {
        private const val CALL = "(?:stringResource|pluralStringResource|getString|getQuantityString)"

        /** A call with up to two levels of nested parentheses in its arguments. */
        private val CALL_THEN_PLUS = Regex("$CALL\\((?:[^()]|\\((?:[^()]|\\([^()]*\\))*\\))*\\)\\s*\\+(?!\\+)")
        private val PLUS_THEN_CALL = Regex("(?<!\\+)\\+\\s*(?:[A-Za-z_][A-Za-z0-9_]*\\.)*$CALL\\(")

        fun concatenates(line: String) = CALL_THEN_PLUS.containsMatchIn(line) || PLUS_THEN_CALL.containsMatchIn(line)
    }
}

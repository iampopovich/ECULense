package io.github.iampopovich.eculense.core.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM tests don't catch regexes that fail only on Android: ICU doesn't know the `(?U)` flag (UNICODE_CHARACTER_CLASS) —
 * PatternSyntaxException "Invalid value for match mode flags" on the first chat question (this is how 0.1.1 crashed on a 14T Pro).
 */
class AndroidRegexCompatTest {
    @Test
    fun `no UNICODE_CHARACTER_CLASS flag in main sources`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val flag = Regex("""\(\?[a-zA-Z]*U""")
        val bad = root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" && "${File.separator}main${File.separator}" in it.path }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> if (flag.containsMatchIn(line) || "UNICODE_CHARACTER_CLASS" in line) "${f.relativeTo(root)}:${i + 1}" else null } }
            .toList()
        assertTrue("Android не поддерживает (?U): $bad", bad.isEmpty())
    }

    @Test
    fun `cyrillic word boundaries still work`() {
        assertTrue(ChatRouter.route("что с аккумулятором").roles.contains(ExpertRoles.ELECTRICAL))
        assertTrue(ChatRouter.days("за 3 мес") != null)
    }
}

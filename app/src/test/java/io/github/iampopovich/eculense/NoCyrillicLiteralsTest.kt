package io.github.iampopovich.eculense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Localization guard: user-visible text lives in string resources, so main Kotlin sources of every module must not
 * contain Cyrillic outside comments. Exceptions are listed in src/test/resources/cyrillic-allowlist.txt (path prefixes
 * relative to the repo root): permanent ones (router keywords, Car Scanner field names) and modules not migrated yet.
 * An allowlist entry that no longer matches any offending file fails the test too, so migrated modules get removed.
 */
class NoCyrillicLiteralsTest {
    private val root: File = File("..").canonicalFile
    private val skippedDirs = setOf("build", ".git", ".gradle", ".idea", ".hermes", "build-logic", "spike")

    private fun allowlist(): List<String> {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("cyrillic-allowlist.txt")) { "cyrillic-allowlist.txt not found" }
        return stream.bufferedReader().readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
    }

    /** Relative path → offending lines ("line: text"). */
    private fun offenders(): Map<String, List<String>> {
        val result = sortedMapOf<String, List<String>>()
        root.walkTopDown()
            .onEnter { it == root || it.name !in skippedDirs }
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                if (!rel.contains("/src/main/")) return@forEach
                val code = KotlinLexer.stripComments(file.readText())
                val lines = code.lines().withIndex()
                    .filter { (_, line) -> CYRILLIC.containsMatchIn(line) }
                    .map { (i, line) -> "${i + 1}: ${line.trim()}" }
                if (lines.isNotEmpty()) result[rel] = lines
            }
        return result
    }

    @Test
    fun `main sources have no cyrillic literals outside the allowlist`() {
        assertTrue("repo root not found from ${File(".").absolutePath}", File(root, "settings.gradle.kts").isFile)
        val allow = allowlist()
        val found = offenders()
        val violations = found.filterKeys { path -> allow.none { path.startsWith(it) } }
        assertTrue(
            "Cyrillic text in main sources: move it to res/values{,-ru}/strings.xml (or allowlist it with a reason):\n" +
                violations.entries.joinToString("\n") { (path, lines) -> "$path\n  " + lines.take(5).joinToString("\n  ") },
            violations.isEmpty(),
        )
        val stale = allow.filter { prefix -> found.keys.none { it.startsWith(prefix) } }
        assertTrue("Allowlist entries without cyrillic literals left — remove them: $stale", stale.isEmpty())
    }

    @Test
    fun `lexer keeps strings and drops comments`() {
        val src = """
            // line comment Привет
            /* block /* nested Привет */ still comment Привет */
            val a = "строка" // tail Привет
            val b = ""${'"'}raw ${'$'}{x /* comment in template code */} сырая""${'"'}
            val c = "url://not-a-comment"
            val d = '/' 
        """.trimIndent()
        val code = KotlinLexer.stripComments(src)
        assertEquals(listOf("строка", "сырая"), CYRILLIC_WORD.findAll(code).map { it.value }.toList())
        assertTrue(code.contains("url://not-a-comment"))
        assertEquals(src.lines().size, code.lines().size)
    }

    private companion object {
        val CYRILLIC = Regex("[А-Яа-яЁё]")
        val CYRILLIC_WORD = Regex("[А-Яа-яЁё]+")
    }
}

/**
 * Minimal Kotlin lexer: blanks out comments (keeping line breaks) and leaves code and string literals intact.
 * Handles nested block comments, escapes, raw strings and string templates with nested code.
 */
internal object KotlinLexer {
    private enum class Mode { CODE, STR, RAW }

    private class Frame(val mode: Mode, var braces: Int = 0)

    private val charLiteral = Regex("""'(\\u[0-9a-fA-F]{4}|\\.|[^'\\\n])'""")

    fun stripComments(s: String): String {
        val out = StringBuilder(s.length)
        val stack = ArrayDeque<Frame>().apply { addLast(Frame(Mode.CODE)) }
        var i = 0
        val n = s.length
        fun blank(from: Int, to: Int) {
            for (k in from until to) out.append(if (s[k] == '\n') '\n' else ' ')
        }
        while (i < n) {
            val frame = stack.last()
            val c = s[i]
            when (frame.mode) {
                Mode.CODE -> when {
                    s.startsWith("//", i) -> {
                        val j = s.indexOf('\n', i).let { if (it < 0) n else it }
                        blank(i, j); i = j
                    }
                    s.startsWith("/*", i) -> {
                        var depth = 1
                        var j = i + 2
                        while (j < n && depth > 0) {
                            when {
                                s.startsWith("/*", j) -> { depth++; j += 2 }
                                s.startsWith("*/", j) -> { depth--; j += 2 }
                                else -> j++
                            }
                        }
                        blank(i, j); i = j
                    }
                    s.startsWith("\"\"\"", i) -> { stack.addLast(Frame(Mode.RAW)); out.append("\"\"\""); i += 3 }
                    c == '"' -> { stack.addLast(Frame(Mode.STR)); out.append(c); i++ }
                    c == '\'' -> {
                        val m = charLiteral.matchAt(s, i)
                        if (m != null) { out.append(m.value); i += m.value.length } else { out.append(c); i++ }
                    }
                    c == '{' -> { frame.braces++; out.append(c); i++ }
                    c == '}' -> {
                        if (frame.braces == 0 && stack.size > 1) stack.removeLast() else frame.braces--
                        out.append(c); i++
                    }
                    else -> { out.append(c); i++ }
                }
                Mode.STR -> when {
                    c == '\\' && i + 1 < n -> { out.append(c).append(s[i + 1]); i += 2 }
                    c == '"' -> { stack.removeLast(); out.append(c); i++ }
                    s.startsWith("\${", i) -> { stack.addLast(Frame(Mode.CODE)); out.append("\${"); i += 2 }
                    else -> { out.append(c); i++ }
                }
                Mode.RAW -> when {
                    s.startsWith("\"\"\"", i) -> {
                        var j = i + 3
                        while (j < n && s[j] == '"') j++ // trailing quotes belong to the content
                        out.append(s, i, j); stack.removeLast(); i = j
                    }
                    s.startsWith("\${", i) -> { stack.addLast(Frame(Mode.CODE)); out.append("\${"); i += 2 }
                    else -> { out.append(c); i++ }
                }
            }
        }
        return out.toString()
    }
}

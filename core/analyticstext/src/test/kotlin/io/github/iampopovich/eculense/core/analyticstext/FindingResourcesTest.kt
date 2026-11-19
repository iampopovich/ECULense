package io.github.iampopovich.eculense.core.analyticstext

import io.github.iampopovich.eculense.core.analytics.FindingKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The UI (string resources) and the AI ([FindingKey.en]) must say the same thing: the English resources equal the English
 * patterns of the keys, and every Russian string has the same placeholders.
 */
class FindingResourcesTest {
    private fun read(path: String): Map<String, Map<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val out = mutableMapOf<String, Map<String, String>>()
        val root = doc.documentElement
        for (i in 0 until root.childNodes.length) {
            val el = root.childNodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name")
            out[name] = when (el.tagName) {
                "string" -> mapOf("" to unescape(el.textContent))
                "plurals" -> (0 until el.childNodes.length).mapNotNull { el.childNodes.item(it) as? Element }
                    .associate { it.getAttribute("quantity") to unescape(it.textContent) }
                else -> continue
            }
        }
        return out
    }

    private fun unescape(s: String) = s.replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\")

    private val placeholder = Regex("""%\d+\$[sd]""")
    private fun placeholders(s: String) = placeholder.findAll(s).map { it.value }.toSortedSet()

    private val en = read("src/main/res/values/findings.xml")
    private val ru = read("src/main/res/values-ru/findings.xml")

    @Test
    fun `english resources equal the AI patterns of every key`() {
        FindingKey.entries.filter { it != FindingKey.RAW }.forEach { k ->
            val res = en["finding_" + k.name.lowercase()] ?: error("no resource for $k")
            if (k.isPlural) {
                assertEquals(k.name, k.enOne, res["one"])
                assertEquals(k.name, k.en, res["other"])
            } else {
                assertEquals(k.name, k.en, res[""])
            }
        }
        assertEquals(FindingKey.entries.size - 1, en.size)
    }

    @Test
    fun `russian strings keep the placeholders and plural forms`() {
        ru.forEach { (name, forms) ->
            val english = en[name] ?: error("values-ru has $name that values lacks")
            val expected = placeholders(english.values.first())
            forms.forEach { (q, text) -> assertEquals("$name/$q", expected, placeholders(text)) }
            if (english.size > 1) assertEquals(name, setOf("one", "few", "many", "other"), forms.keys)
        }
        // strings without Cyrillic are not translated (translatable="false"), everything else is
        val missing = en.keys - ru.keys
        assertTrue(missing.toString(), missing.all { k -> en.getValue(k).values.none { Regex("[A-Za-z]{3,}").containsMatchIn(it.replace(placeholder, "")) } })
    }
}

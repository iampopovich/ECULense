package io.github.iampopovich.eculense.feature.aichat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlEntitiesTest {
    @Test
    fun `named and numeric entities are decoded`() {
        assertEquals("Температура ОЖ стабильно >105 °C", HtmlEntities.decode("Температура ОЖ стабильно &gt;105 &deg;C"))
        assertEquals("a < b & \"c\" 'd'", HtmlEntities.decode("a &lt; b &amp; &quot;c&quot; &#39;d&#x27;"))
    }

    @Test
    fun `decoding is single pass and leaves unknown text alone`() {
        assertEquals("&gt;", HtmlEntities.decode("&amp;gt;"))
        assertEquals("A & B, &foo; &gt", HtmlEntities.decode("A & B, &foo; &gt"))
        assertEquals("без сущностей", HtmlEntities.decode("без сущностей"))
    }

    @Test
    fun `incomplete entity at the stream tail is held back`() {
        assertEquals("ОЖ ", HtmlEntities.decode("ОЖ &g", complete = false))
        assertEquals("ОЖ ", HtmlEntities.decode("ОЖ &", complete = false))
        assertEquals("ОЖ >1", HtmlEntities.decode("ОЖ &gt;1", complete = false))
        assertEquals("ОЖ &g", HtmlEntities.decode("ОЖ &g", complete = true))
    }

    @Test
    fun `streamed prefixes are always prefixes of the final text`() {
        val full = "Темп. &gt;105 &deg;C, A & B, &#8805;90 и &amp;lt; конец"
        val final = HtmlEntities.decode(full)
        for (i in 0..full.length) {
            val partial = HtmlEntities.decode(full.substring(0, i), complete = false)
            assertTrue("«$partial» не префикс «$final»", final.startsWith(partial))
        }
    }
}

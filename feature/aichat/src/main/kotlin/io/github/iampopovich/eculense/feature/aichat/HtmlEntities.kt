package io.github.iampopovich.eculense.feature.aichat

/**
 * Models sometimes send HTML entities (`&gt;105 °C`), and the markdown renderer deliberately doesn't decode them
 * (multiplatform-markdown-renderer: getUnescapedTextInNode → processEntities = false), so the screen showed
 * a literal "&gt;". We decode them ourselves before parsing Markdown.
 *
 * complete = false means the response is still streaming: an unfinished entity at the tail ("…&g") is held until the next chunk.
 * That way the result for a prefix is always a prefix of the result for the full text: the renderer's streaming state
 * is only ever appended to.
 */
internal object HtmlEntities {
    private val entity = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,7});")
    private val partialTail = Regex("&(#[xX]?[0-9a-fA-F]{0,7}|[a-zA-Z][a-zA-Z0-9]{0,7})?$")
    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "ndash" to "–", "mdash" to "—", "laquo" to "«", "raquo" to "»", "hellip" to "…", "deg" to "°",
        "plusmn" to "±", "times" to "×", "le" to "≤", "ge" to "≥", "ne" to "≠", "asymp" to "≈", "rarr" to "→", "larr" to "←",
    )

    fun decode(text: String, complete: Boolean = true): String {
        if ('&' !in text) return text
        val body = if (complete) text else text.replace(partialTail, "")
        return entity.replace(body) { m ->
            val name = m.groupValues[1]
            when {
                name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)?.codePointString()
                name.startsWith("#") -> name.drop(1).toIntOrNull()?.codePointString()
                else -> named[name]
            } ?: m.value
        }
    }

    private fun Int.codePointString(): String? =
        if (this in 1..0x10FFFF && this !in 0xD800..0xDFFF) String(Character.toChars(this)) else null
}

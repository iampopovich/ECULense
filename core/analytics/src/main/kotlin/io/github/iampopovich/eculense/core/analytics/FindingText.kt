package io.github.iampopovich.eculense.core.analytics

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One localizable phrase of a finding: a [key] plus typed [args]. The rules never build UI text: the Android UI renders
 * the key with string resources (module :core:analyticstext), the AI gets English via [FindingEnglish].
 * [FindingKey.RAW] carries text as is: DTC codes, and findings stored by versions before localization.
 */
data class FindingText(val key: FindingKey, val args: List<FindingArg> = emptyList()) {
    constructor(key: FindingKey, vararg args: FindingArg) : this(key, args.toList())

    /** For plural keys: the quantity is the first argument. */
    val quantity: Int? get() = if (key.isPlural) (args.firstOrNull() as? FindingArg.Count)?.n else null

    companion object {
        fun raw(text: String) = FindingText(FindingKey.RAW, listOf(FindingArg.Raw(text)))
    }
}

/** Typed argument of a [FindingText]; numbers and dates are formatted by the renderer in its locale. */
sealed interface FindingArg {
    /** A decimal number: [decimals] digits after the separator, [signed] adds «+» to positive values. */
    data class Num(val value: Double, val decimals: kotlin.Int = 1, val signed: Boolean = false) : FindingArg

    /** An integer, passed to %d (and the plural quantity when it is the first argument). */
    data class Count(val n: kotlin.Int) : FindingArg

    /** A calendar date (rendered in the device time zone). */
    data class Date(val ms: Long) : FindingArg

    /** «date – date», or one date when both fall on the same day. */
    data class DateRange(val fromMs: Long, val toMs: Long) : FindingArg

    /** Display name of a sensor id from [SensorIds]. */
    data class Sensor(val id: String) : FindingArg

    /** Text that is the same in every language: DTC codes, «P0171×2». */
    data class Raw(val text: String) : FindingArg

    /** A nested phrase; null renders as an empty string. */
    data class Text(val text: FindingText?) : FindingArg

    /** Several phrases joined with [separator] (punctuation only). */
    data class Join(val items: List<FindingText>, val separator: String = ", ") : FindingArg
}

/** Finding evidence: label and value as localizable phrases. A DTC evidence has the raw code as its label. */
data class Evidence(val label: FindingText, val value: FindingText) {
    /** The label when it is raw text (a DTC code), else null. */
    val rawLabel: String? get() = if (label.key == FindingKey.RAW) (label.args.singleOrNull() as? FindingArg.Raw)?.text else null
}

/**
 * Plain English rendering for the AI (prompts and tool answers are English, see the localization plan) and for logs.
 * Numbers use a decimal point; dates are «d MMM yyyy» in English.
 */
object FindingEnglish {
    private val locale = Locale.ENGLISH
    private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", locale)

    fun render(t: FindingText, zone: ZoneId = ZoneId.systemDefault()): String {
        if (t.key == FindingKey.RAW) return t.args.joinToString("") { arg(it, zone).toString() }
        val pattern = if (t.key.enOne != null && t.quantity == 1) t.key.enOne else t.key.en
        return String.format(locale, pattern, *t.args.map { arg(it, zone) }.toTypedArray())
    }

    fun title(f: Finding, zone: ZoneId = ZoneId.systemDefault()): String = render(f.title, zone)

    fun message(f: Finding, zone: ZoneId = ZoneId.systemDefault()): String = f.message.joinToString(" ") { render(it, zone) }

    fun evidence(f: Finding, zone: ZoneId = ZoneId.systemDefault()): Map<String, String> =
        f.evidence.associate { render(it.label, zone) to render(it.value, zone) }

    private fun arg(a: FindingArg, zone: ZoneId): Any = when (a) {
        is FindingArg.Num -> number(a)
        is FindingArg.Count -> a.n
        is FindingArg.Date -> date(a.ms, zone)
        is FindingArg.DateRange -> range(a.fromMs, a.toMs, zone)
        is FindingArg.Sensor -> SensorIds.NAMES[a.id] ?: a.id
        is FindingArg.Raw -> a.text
        is FindingArg.Text -> a.text?.let { render(it, zone) }.orEmpty()
        is FindingArg.Join -> a.items.joinToString(a.separator) { render(it, zone) }
    }

    private fun number(a: FindingArg.Num): String =
        (if (a.signed && a.value > 0) "+" else "") + String.format(locale, "%.${a.decimals}f", a.value)

    private fun date(ms: Long, zone: ZoneId): String = dateFormat.format(Instant.ofEpochMilli(ms).atZone(zone))

    private fun range(from: Long, to: Long, zone: ZoneId): String =
        if (date(from, zone) == date(to, zone)) date(from, zone) else "${date(from, zone)} – ${date(to, zone)}"
}

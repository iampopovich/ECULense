package io.github.iampopovich.eculense.core.analyticstext

import android.content.Context
import io.github.iampopovich.eculense.core.analytics.Evidence
import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.FindingArg
import io.github.iampopovich.eculense.core.analytics.FindingKey
import io.github.iampopovich.eculense.core.analytics.FindingText
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import io.github.iampopovich.eculense.core.model.MaintenanceKind
import io.github.iampopovich.eculense.core.model.MaintenanceNote
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * UI text of typed analytics results, in the app language (string resources, values = English, values-ru = Russian).
 * Numbers and dates are formatted for the context's locale. The AI gets English from [io.github.iampopovich.eculense.core.analytics.FindingEnglish].
 */
object AnalyticsText {
    fun render(context: Context, t: FindingText, zone: ZoneId = ZoneId.systemDefault()): String {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val args = t.args.map { arg(context, it, locale, zone) }
        if (t.key == FindingKey.RAW) return args.joinToString("")
        val id = t.key.resId() ?: return args.joinToString(" ")
        return if (t.key.isPlural) {
            context.resources.getQuantityString(id, t.quantity ?: 0, *args.toTypedArray())
        } else {
            context.getString(id, *args.toTypedArray())
        }
    }

    fun title(context: Context, f: Finding): String = render(context, f.title)

    fun message(context: Context, f: Finding): String = f.message.joinToString(" ") { render(context, it) }

    fun label(context: Context, e: Evidence): String = render(context, e.label)

    fun value(context: Context, e: Evidence): String = render(context, e.value)

    fun severity(context: Context, s: Severity): String = context.getString(s.resId())

    /** Resource ids, for UiText held by ViewModels. */
    fun severityRes(s: Severity): Int = s.resId()

    fun maintenanceKindRes(kind: MaintenanceKind): Int = kind.resId()

    fun maintenanceNoteRes(note: MaintenanceNote): Int = note.resId()

    fun maintenanceKind(context: Context, kind: MaintenanceKind): String = context.getString(kind.resId())

    fun maintenanceNote(context: Context, note: MaintenanceNote): String = context.getString(note.resId())

    /** The user's own title wins; a template work shows the localized name of its kind. */
    fun maintenanceTitle(context: Context, item: MaintenanceItem): String =
        item.title.ifBlank { item.kind?.let { maintenanceKind(context, it) }.orEmpty() }

    /** The user's own note wins over the template note; null when there is none. */
    fun maintenanceNote(context: Context, item: MaintenanceItem): String? =
        item.note?.takeIf { it.isNotBlank() } ?: item.noteKind?.let { maintenanceNote(context, it) }

    private fun arg(context: Context, a: FindingArg, locale: Locale, zone: ZoneId): Any = when (a) {
        is FindingArg.Num -> (if (a.signed && a.value > 0) "+" else "") + String.format(locale, "%.${a.decimals}f", a.value)
        is FindingArg.Count -> a.n
        is FindingArg.Date -> date(a.ms, locale, zone)
        is FindingArg.DateRange -> {
            val from = date(a.fromMs, locale, zone)
            val to = date(a.toMs, locale, zone)
            if (from == to) from else "$from – $to"
        }
        is FindingArg.Sensor -> SENSOR_NAME_RES[a.id]?.let(context::getString) ?: a.id
        is FindingArg.Raw -> a.text
        is FindingArg.Text -> a.text?.let { render(context, it, zone) }.orEmpty()
        is FindingArg.Join -> a.items.joinToString(a.separator) { render(context, it, zone) }
    }

    private fun date(ms: Long, locale: Locale, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(Instant.ofEpochMilli(ms).atZone(zone))
}

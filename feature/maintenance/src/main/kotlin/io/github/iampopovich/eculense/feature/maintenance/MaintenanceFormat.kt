package io.github.iampopovich.eculense.feature.maintenance

import androidx.annotation.StringRes
import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import io.github.iampopovich.eculense.core.analytics.MaintenanceStatus
import io.github.iampopovich.eculense.core.analyticstext.AnalyticsText
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import io.github.iampopovich.eculense.core.ui.UiText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Texts for the «Maintenance» screen as [UiText]: numbers use a non-breaking space as the thousands separator. */
internal object MaintenanceFormat {
    // Numeric input pattern: independent of the app language.
    private val dateInput = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT)

    /** Whole number in the current locale, thousands separated by a non-breaking space. */
    fun number(v: Number): String =
        String.format(Locale.getDefault(), "%,d", v.toDouble().roundToLong()).replace('\u202F', '\u00A0').replace(' ', '\u00A0')

    fun km(v: Number): UiText = UiText.Res(R.string.maint_km, listOf(number(v)))

    /** A small mileage increment is shown with tenths («0.2 km»), otherwise a short trip looks like «plus 0 km». */
    fun addedKm(v: Double): UiText =
        if (v < 10.0) UiText.Res(R.string.maint_km, listOf(String.format(Locale.getDefault(), "%.1f", v))) else km(v)

    @StringRes
    fun status(s: MaintenanceStatus): Int = when (s) {
        MaintenanceStatus.OVERDUE -> R.string.maint_status_overdue
        MaintenanceStatus.SOON -> R.string.maint_status_soon
        MaintenanceStatus.OK -> R.string.maint_status_ok
        MaintenanceStatus.UNKNOWN -> R.string.maint_status_unknown
    }

    /** The user's title, or the localized name of a template work. */
    fun title(item: MaintenanceItem): UiText = when {
        item.title.isNotBlank() -> UiText.Raw(item.title)
        item.kind != null -> UiText.Res(AnalyticsText.maintenanceKindRes(item.kind!!))
        else -> UiText.Raw("")
    }

    /** The user's note, or the localized template note; null when there is none. */
    fun note(item: MaintenanceItem): UiText? = when {
        !item.note.isNullOrBlank() -> UiText.Raw(item.note!!)
        item.noteKind != null -> UiText.Res(AnalyticsText.maintenanceNoteRes(item.noteKind!!))
        else -> null
    }

    fun interval(item: MaintenanceItem): UiText {
        val parts = listOfNotNull(item.intervalKm?.let { km(it) }, item.intervalMonths?.let { UiText.Res(R.string.maint_months_short, listOf(it)) })
        return when (parts.size) {
            0 -> UiText.Res(R.string.maint_interval_none)
            1 -> UiText.Res(R.string.maint_interval_every, parts)
            else -> UiText.Res(R.string.maint_interval_every_or, parts)
        }
    }

    /** null — never marked yet (then [remaining] gives the hint «Mark when it was done»). */
    fun lastDone(item: MaintenanceItem, zone: ZoneId): UiText? {
        val parts = listOfNotNull<Any>(item.lastDoneAtMs?.let { date(it, zone) }, item.lastDoneKm?.let { km(it) })
        return when (parts.size) {
            0 -> null
            1 -> UiText.Res(R.string.maint_last_done, parts)
            else -> UiText.Res(R.string.maint_last_done_two, parts)
        }
    }

    /** «1,200 km left · in 3 mo.», «overdue by 300 km», «Mark when it was done»; the parts are joined with « · ». */
    fun remaining(d: MaintenanceDue): List<UiText> {
        if (d.status == MaintenanceStatus.UNKNOWN) {
            return listOf(
                when {
                    d.item.intervalKm == null && d.item.intervalMonths == null -> UiText.Res(R.string.maint_set_interval)
                    d.item.lastDoneAtMs == null && d.item.lastDoneKm == null -> UiText.Res(R.string.maint_mark_when_done)
                    else -> UiText.Res(R.string.maint_enter_odometer_for_km)
                },
            )
        }
        return buildList {
            d.kmLeft?.let { left -> add(if (left <= 0) UiText.Res(R.string.maint_overdue_by, listOf(km(abs(left)))) else UiText.Res(R.string.maint_km_left, listOf(km(left)))) }
            d.daysLeft?.let { days -> add(days(days)) }
        }
    }

    private fun days(d: Long): UiText = when {
        d == 0L -> UiText.Res(R.string.maint_due_today)
        d < 0 -> if (-d > 60) UiText.Res(R.string.maint_due_months_ago, listOf((-d / 30).toInt())) else UiText.Plural(R.plurals.maint_due_days_ago, (-d).toInt())
        d > 60 -> UiText.Res(R.string.maint_in_months, listOf((d / 30).toInt()))
        else -> UiText.Plural(R.plurals.maint_in_days, d.toInt())
    }

    const val SEPARATOR = " · "

    fun date(ms: Long, zone: ZoneId): String = dateInput.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** Mileage from the input field: «120 000» → 120000; empty/garbage/outside 0..5 000 000 → null. */
    fun parseKm(text: String): Long? = text.filterNot { it.isWhitespace() || it == '\u00A0' || it == ',' }.toLongOrNull()?.takeIf { it in 0..5_000_000 }

    fun parseInt(text: String, max: Int): Int? = text.filterNot { it.isWhitespace() || it == '\u00A0' }.toIntOrNull()?.takeIf { it in 1..max }

    /** «dd.MM.yyyy» at local noon (so a time zone change doesn't shift the date); future dates are not allowed. */
    fun parseDate(text: String, zone: ZoneId, nowMs: Long): Long? = runCatching {
        val d = LocalDate.parse(text.trim(), dateInput)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        if (d.isAfter(today)) null else d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()
}

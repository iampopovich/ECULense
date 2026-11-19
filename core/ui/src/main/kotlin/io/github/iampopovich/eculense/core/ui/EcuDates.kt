package io.github.iampopovich.eculense.core.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Dates in the UI follow the app language: formatters are built per call from [Locale.getDefault]
 * (AppCompat/the system update the default locale when the user switches the app language).
 */
object EcuDates {
    /** en: «Jun 11, 2026, 10:07 AM»; ru: «11 июн. 2026 г., 10:07». */
    fun dateTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
            .format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** en: «Jun 11, 2026»; ru: «11 июн. 2026 г.». */
    fun date(epochMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            .format(Instant.ofEpochMilli(epochMs).atZone(zone))
}

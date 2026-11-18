package io.github.iampopovich.eculense.core.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Даты в интерфейсе — всегда по-русски, как и весь UI, независимо от языка системы
 * (иначе на английском телефоне получается «Jun 11, 2026 10:07 AM» среди русского текста).
 */
object EcuDates {
    val RU: Locale = Locale.forLanguageTag("ru")

    private val dateTimeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(RU)
    private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(RU)

    /** «11 июн. 2026 г., 10:07». */
    fun dateTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dateTimeFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** «11 июн. 2026 г.». */
    fun date(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dateFormat.format(Instant.ofEpochMilli(epochMs).atZone(zone))
}

package io.github.iampopovich.eculense.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

class EcuDatesTest {
    private val ms = LocalDateTime.of(2026, 6, 11, 10, 7).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val ru = Locale.forLanguageTag("ru")

    @Test
    fun `english locale gives english month and 12-hour time`() {
        val text = EcuDates.dateTime(ms, ZoneOffset.UTC, Locale.US)
        assertTrue(text, text.contains("Jun"))
        assertTrue(text, text.contains("2026"))
        assertTrue(text, text.contains("10:07"))
        assertTrue(text, text.contains("AM"))
        assertTrue(EcuDates.date(ms, ZoneOffset.UTC, Locale.US).contains("Jun"))
    }

    @Test
    fun `russian locale gives russian month and 24-hour time`() {
        val text = EcuDates.dateTime(ms, ZoneOffset.UTC, ru)
        assertTrue(text, text.contains("июн"))
        assertTrue(text, text.contains("10:07"))
        assertFalse(text, text.contains("AM"))
        assertTrue(EcuDates.date(ms, ZoneOffset.UTC, ru).contains("июн"))
    }

    @Test
    fun `default locale is used when none is passed`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(ru)
            assertTrue(EcuDates.date(ms, ZoneOffset.UTC).contains("июн"))
            Locale.setDefault(Locale.US)
            assertTrue(EcuDates.date(ms, ZoneOffset.UTC).contains("Jun"))
        } finally {
            Locale.setDefault(saved)
        }
    }
}

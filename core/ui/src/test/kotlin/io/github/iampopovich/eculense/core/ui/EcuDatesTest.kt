package io.github.iampopovich.eculense.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

class EcuDatesTest {
    private val ms = LocalDateTime.of(2026, 6, 11, 10, 7).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `date and time are russian even when the system locale is english`() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            val text = EcuDates.dateTime(ms, ZoneOffset.UTC)
            assertTrue(text, text.contains("июн"))
            assertTrue(text, text.contains("2026"))
            assertTrue(text, text.contains("10:07"))
            assertFalse(text, text.contains("AM"))
            assertTrue(EcuDates.date(ms, ZoneOffset.UTC).contains("июн"))
        } finally {
            Locale.setDefault(saved)
        }
    }
}

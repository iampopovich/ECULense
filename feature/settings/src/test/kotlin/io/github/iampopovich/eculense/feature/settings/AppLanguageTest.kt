package io.github.iampopovich.eculense.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun `language tags map to picker options`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromTag("ru-RU"))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromTag("RU"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("de-DE"))
    }

    @Test
    fun `system default goes first and has no tag`() {
        assertEquals(listOf(null, "en", "ru"), AppLanguage.entries.map { it.tag })
    }
}

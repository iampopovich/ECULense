package io.github.iampopovich.eculense.core.analyticstext

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.FindingArg
import io.github.iampopovich.eculense.core.analytics.FindingKey
import io.github.iampopovich.eculense.core.analytics.FindingText
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import io.github.iampopovich.eculense.core.model.MaintenanceKind
import io.github.iampopovich.eculense.core.model.MaintenanceNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneOffset
import java.util.Locale

/** Renders typed findings in a fixed locale (en / ru) through the real resources. */
@RunWith(AndroidJUnit4::class)
class AnalyticsTextTest {
    private fun context(tag: String): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(config)
    }

    private val overheat = Finding(
        "coolant-overheat", Severity.WARNING, FindingText(FindingKey.TITLE_COOLANT_OVERHEAT),
        FindingText(FindingKey.COOLANT_OVERHEAT, FindingArg.Count(3), FindingArg.Num(105.0, 0), FindingArg.Count(30)),
    )

    @Test
    fun englishAndRussianWithPluralsAndLocaleNumbers() {
        val en = context("en")
        assertEquals("Engine overheating", AnalyticsText.title(en, overheat))
        assertEquals("Coolant stayed at ≥ 105 °C for longer than 30 s in 3 recordings.", AnalyticsText.message(en, overheat))
        val ru = context("ru")
        assertEquals("Перегрев двигателя", AnalyticsText.title(ru, overheat))
        assertEquals("Температура ОЖ держалась ≥ 105 °C дольше 30 с в 3 записях.", AnalyticsText.message(ru, overheat))
        val volts = FindingText(FindingKey.VALUE_VOLTS, FindingArg.Num(12.8))
        assertEquals("12.8 V", AnalyticsText.render(en, volts))
        assertEquals("12,8 В", AnalyticsText.render(ru, volts))
        val range = FindingText(FindingKey.DTC_OCCURRENCES, FindingArg.Count(2), FindingArg.DateRange(0, 0))
        assertTrue(AnalyticsText.render(ru, range, ZoneOffset.UTC).startsWith("2 раза: "))
        assertEquals("Критично", AnalyticsText.severity(ru, Severity.CRITICAL))
    }

    @Test
    fun maintenanceTemplateNamesFollowTheLanguageUserTextWins() {
        val oil = MaintenanceItem(carId = 1, title = "", kind = MaintenanceKind.OIL_AND_FILTER, noteKind = MaintenanceNote.OIL_SEVERE_CONDITIONS)
        assertEquals("Engine oil and filter", AnalyticsText.maintenanceTitle(context("en"), oil))
        assertEquals("Масло и масляный фильтр двигателя", AnalyticsText.maintenanceTitle(context("ru"), oil))
        assertEquals("My oil", AnalyticsText.maintenanceTitle(context("ru"), oil.copy(title = "My oil", note = "own")))
        assertEquals("own", AnalyticsText.maintenanceNote(context("ru"), oil.copy(note = "own")))
    }
}

package io.github.iampopovich.eculense.core.obdtext

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iampopovich.eculense.core.obd.ObdError
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Renders typed OBD errors in a fixed locale (en / ru) through the real resources. */
@RunWith(AndroidJUnit4::class)
class ObdTextRenderTest {
    private fun context(tag: String): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(config)
    }

    @Test
    fun linkLostNestsTheCause() {
        val e = ObdError.LinkLost(ObdError.AdapterClosed)
        assertEquals("Connection to the adapter lost: The adapter closed the connection", ObdText.error(e).asString(context("en")))
        assertEquals("Связь с адаптером потеряна: Адаптер закрыл соединение", ObdText.error(e).asString(context("ru")))
    }

    @Test
    fun timeoutKeepsTheCommand() {
        assertEquals("Адаптер не ответил на '0100'", ObdText.error(ObdError.Timeout("0100")).asString(context("ru")))
    }
}

package io.github.iampopovich.eculense.core.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class UiTextTest {
    private fun context(tag: String): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(config)
    }

    @Test
    fun resResolvesForTheContextLocale() {
        assertEquals("Delete", UiText.Res(R.string.common_delete).asString(context("en")))
        assertEquals("Удалить", UiText.Res(R.string.common_delete).asString(context("ru")))
    }

    @Test
    fun rawIsShownAsIs() {
        assertEquals("ELM327 v1.5", UiText.Raw("ELM327 v1.5").asString(context("en")))
    }

    @Test
    fun joinedResolvesEachPart() {
        val joined = UiText.Joined(listOf(UiText.Res(R.string.common_save), UiText.Raw("x")))
        assertEquals("Save · x", joined.asString(context("en")))
        assertEquals("Сохранить · x", joined.asString(context("ru")))
    }
}

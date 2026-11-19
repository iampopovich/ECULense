package io.github.iampopovich.eculense

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.iampopovich.eculense.core.model.AppLanguage
import org.koin.dsl.module

/**
 * App language = the per-app locale picked in Settings, or the system locale adjusted to the app's supported
 * locales when none is picked. Evaluated on every call: the language may change while the app runs.
 */
internal object AndroidAppLanguage : AppLanguage {
    override fun tag(): String {
        val locale = AppCompatDelegate.getApplicationLocales()[0] ?: LocaleListCompat.getAdjustedDefault()[0]
        return locale?.toLanguageTag()?.takeIf { it.isNotBlank() && it != "und" } ?: "en"
    }
}

val appLanguageModule = module {
    single<AppLanguage> { AndroidAppLanguage }
}

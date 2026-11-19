package io.github.iampopovich.eculense.feature.settings

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private fun string(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun showsThreeOptionsWithTheSelectedOneChecked() {
        rule.setContent { LanguageScreen(AppLanguage.RUSSIAN, onSelect = {}) }
        rule.onNodeWithText(string(R.string.settings_language_system)).assertExists()
        rule.onNodeWithText(string(R.string.settings_language_english)).assertExists()
        rule.onNodeWithTag("language-russian").assertIsSelected()
        rule.onNodeWithTag("language-system").assertIsNotSelected()
    }

    @Test
    fun tapReportsTheOption() {
        val picked = mutableListOf<AppLanguage>()
        rule.setContent { LanguageScreen(AppLanguage.SYSTEM, onSelect = { picked += it }) }
        rule.onNodeWithTag("language-english").performClick()
        assertEquals(listOf(AppLanguage.ENGLISH), picked)
    }
}

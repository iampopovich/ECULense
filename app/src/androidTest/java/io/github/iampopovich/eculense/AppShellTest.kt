package io.github.iampopovich.eculense

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppShellTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** С задачи 1.15 первая вкладка — «Адаптер» (order = 0), панель открывается из меню. */
    @Test
    fun dashboardIsReachableFromMenu() {
        rule.onNodeWithText("Адаптер").assertIsDisplayed()
        rule.onNodeWithText("Панель").performClick()
        rule.onNodeWithText("Нет подключения к адаптеру").assertIsDisplayed()
    }
}

package io.github.iampopovich.eculense

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppShellTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun dashboardIsShownOnStart() {
        rule.onNodeWithText("Нет подключения к адаптеру").assertIsDisplayed()
    }
}

package io.github.iampopovich.eculense

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Four tabs: «Панель» (start), «Статистика», «Пит-стоп», «Настройки». The adapter screen is an item in the settings hub. */
@RunWith(AndroidJUnit4::class)
class AppShellTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** Bottom navigation item (the hub title with the same text is not selectable). */
    private fun tab(label: String): SemanticsNodeInteraction = rule.onNode(hasText(label) and isSelectable())

    /** The adapter screen requests permissions: grant them up front, otherwise the system dialog tears down the Compose hierarchy. */
    @Before
    fun grantPermissions() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        if (Build.VERSION.SDK_INT >= 31) automation.grantRuntimePermission(pkg, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(pkg, Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun startsOnDashboard() {
        tab("Панель").assertIsSelected()
        tab("Статистика").assertIsDisplayed()
        tab("Пит-стоп").assertIsDisplayed()
        tab("Настройки").assertIsDisplayed()
        rule.onNodeWithText("Нет подключения к адаптеру").assertIsDisplayed()
    }

    @Test
    fun settingsTabListsAdapterEntry() {
        tab("Настройки").performClick()
        rule.onNodeWithText("Адаптер").assertIsDisplayed()
        rule.onNodeWithText("Выбор и подключение ELM327").assertIsDisplayed()
        // AI module is wired in: AI settings item in the hub.
        rule.onNodeWithText("ИИ-помощник").assertIsDisplayed()
        rule.onNodeWithText("Провайдер, адрес, ключ, модель").assertIsDisplayed()
    }

    @Test
    fun pitstopTabShowsHub() {
        tab("Пит-стоп").performClick()
        rule.onNodeWithText("Ошибки (DTC)").assertIsDisplayed()
        rule.onNodeWithText("Поездки").assertIsDisplayed()
        // Exactly one AI card (onNodeWithText fails on duplicates), no placeholder.
        rule.onNodeWithText("ИИ-помощник").assertIsDisplayed()
        rule.onNodeWithText("ИИ-помощник появится позже").assertDoesNotExist()
    }

    @Test
    fun dtcScreenOpensFromPitstopAndKeepsTabSelected() {
        tab("Пит-стоп").performClick()
        rule.onNodeWithText("Ошибки (DTC)").performClick()
        rule.onNodeWithText("Прочитать ошибки").assertIsDisplayed()
        tab("Пит-стоп").assertIsSelected()
    }

    @Test
    fun adapterScreenOpensFromSettingsAndKeepsTabSelected() {
        tab("Настройки").performClick()
        rule.onNodeWithText("Адаптер").performClick()
        rule.onNodeWithText("Обновить список").assertIsDisplayed()
        tab("Настройки").assertIsSelected()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Выбор и подключение ELM327").assertIsDisplayed()
    }

    @Test
    fun adapterScreenOpensFromDashboardButton() {
        rule.onNodeWithText("Подключить").performClick()
        rule.onNodeWithText("Обновить список").assertIsDisplayed()
        tab("Панель").assertIsSelected()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Нет подключения к адаптеру").assertIsDisplayed()
    }
}

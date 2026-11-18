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

/** Три вкладки: «Панель» (старт), «Пит-стоп», «Настройки». Экран адаптера — пункт хаба настроек. */
@RunWith(AndroidJUnit4::class)
class AppShellTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** Пункт нижнего меню (заголовок хаба с тем же текстом не выбираемый). */
    private fun tab(label: String): SemanticsNodeInteraction = rule.onNode(hasText(label) and isSelectable())

    /** Экран адаптера просит разрешения: выдаём заранее, иначе системный диалог закроет Compose-иерархию. */
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
        tab("Пит-стоп").assertIsDisplayed()
        tab("Настройки").assertIsDisplayed()
        rule.onNodeWithText("Нет подключения к адаптеру").assertIsDisplayed()
    }

    @Test
    fun settingsTabListsAdapterEntry() {
        tab("Настройки").performClick()
        rule.onNodeWithText("Адаптер").assertIsDisplayed()
        rule.onNodeWithText("Выбор и подключение ELM327").assertIsDisplayed()
    }

    @Test
    fun pitstopTabShowsHub() {
        tab("Пит-стоп").performClick()
        rule.onNodeWithText("ИИ-помощник появится позже").assertIsDisplayed()
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

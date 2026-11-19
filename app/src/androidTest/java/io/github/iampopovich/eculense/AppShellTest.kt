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
import io.github.iampopovich.eculense.feature.aichat.R as AiChatR
import io.github.iampopovich.eculense.feature.aisettings.R as AiSettingsR
import io.github.iampopovich.eculense.feature.connect.R as ConnectR
import io.github.iampopovich.eculense.feature.dashboard.R as DashR
import io.github.iampopovich.eculense.feature.dtc.R as DtcR
import io.github.iampopovich.eculense.feature.pitstop.R as PitstopR
import io.github.iampopovich.eculense.feature.sessions.R as SessionsR
import io.github.iampopovich.eculense.feature.settings.R as SettingsR
import io.github.iampopovich.eculense.feature.stats.R as StatsR

/**
 * Four tabs: Dashboard (start), Statistics, Pit stop, Settings. The adapter screen is an item in the settings hub.
 * Texts come from the feature resources, so the test passes in any app/device language.
 */
@RunWith(AndroidJUnit4::class)
class AppShellTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun string(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun text(id: Int): SemanticsNodeInteraction = rule.onNodeWithText(string(id))

    /** Bottom navigation item (the hub title with the same text is not selectable). */
    private fun tab(label: Int): SemanticsNodeInteraction = rule.onNode(hasText(string(label)) and isSelectable())

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
        tab(DashR.string.dash_tab).assertIsSelected()
        tab(StatsR.string.stats_tab).assertIsDisplayed()
        tab(PitstopR.string.pitstop_tab).assertIsDisplayed()
        tab(SettingsR.string.settings_tab).assertIsDisplayed()
        text(DashR.string.dash_not_connected).assertIsDisplayed()
    }

    @Test
    fun settingsTabListsAdapterEntry() {
        tab(SettingsR.string.settings_tab).performClick()
        text(ConnectR.string.connect_hub_title).assertIsDisplayed()
        text(ConnectR.string.connect_hub_subtitle).assertIsDisplayed()
        // AI module is wired in: AI settings item in the hub.
        text(AiSettingsR.string.aisettings_hub_title).assertIsDisplayed()
        text(AiSettingsR.string.aisettings_hub_subtitle).assertIsDisplayed()
    }

    @Test
    fun pitstopTabShowsHub() {
        tab(PitstopR.string.pitstop_tab).performClick()
        text(DtcR.string.dtc_hub_title).assertIsDisplayed()
        text(SessionsR.string.sessions_hub_title).assertIsDisplayed()
        // Exactly one AI card (onNodeWithText fails on duplicates), no placeholder.
        text(AiChatR.string.aichat_hub_title).assertIsDisplayed()
        text(PitstopR.string.pitstop_assistant_later_title).assertDoesNotExist()
    }

    @Test
    fun dtcScreenOpensFromPitstopAndKeepsTabSelected() {
        tab(PitstopR.string.pitstop_tab).performClick()
        text(DtcR.string.dtc_hub_title).performClick()
        text(DtcR.string.dtc_read).assertIsDisplayed()
        tab(PitstopR.string.pitstop_tab).assertIsSelected()
    }

    @Test
    fun adapterScreenOpensFromSettingsAndKeepsTabSelected() {
        tab(SettingsR.string.settings_tab).performClick()
        text(ConnectR.string.connect_hub_title).performClick()
        text(ConnectR.string.connect_refresh).assertIsDisplayed()
        tab(SettingsR.string.settings_tab).assertIsSelected()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        text(ConnectR.string.connect_hub_subtitle).assertIsDisplayed()
    }

    @Test
    fun adapterScreenOpensFromDashboardButton() {
        text(DashR.string.dash_connect).performClick()
        text(ConnectR.string.connect_refresh).assertIsDisplayed()
        tab(DashR.string.dash_tab).assertIsSelected()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        text(DashR.string.dash_not_connected).assertIsDisplayed()
    }
}

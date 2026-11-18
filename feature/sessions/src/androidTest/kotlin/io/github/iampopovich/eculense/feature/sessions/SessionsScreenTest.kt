package io.github.iampopovich.eculense.feature.sessions

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iampopovich.eculense.core.model.Session
import io.github.iampopovich.eculense.core.model.SessionSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val trip = Session(7, "Поездка у дома", 1_700_000_000_000, 1_700_000_060_000, SessionSource.LIVE)

    @Test
    fun emptyListExplainsHowToRecord() {
        rule.setContent { SessionsScreen(emptyList(), onOpen = {}, onDelete = {}) }
        rule.onNodeWithText("Записей пока нет", substring = true).assertExists()
    }

    @Test
    fun sourceIsShownInRussian() {
        rule.setContent { SessionsScreen(listOf(trip), onOpen = {}, onDelete = {}) }
        rule.onNodeWithText("запись с адаптера", substring = true).assertExists()
        rule.onNodeWithText("LIVE", substring = true).assertDoesNotExist()
    }

    @Test
    fun rowOpensAndDeletes() {
        val opened = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()
        rule.setContent { SessionsScreen(listOf(trip), onOpen = { opened += it.id }, onDelete = { deleted += it.id }) }
        rule.onNodeWithText("Поездка у дома").performClick()
        rule.onNodeWithText("Удалить").performClick()
        assertEquals(listOf(7L), opened)
        assertEquals(listOf(7L), deleted)
    }

    @Test
    fun importLinkIsAvailableEvenWithoutRecordings() {
        var clicks = 0
        rule.setContent { SessionsScreen(emptyList(), onOpen = {}, onDelete = {}, onImport = { clicks++ }) }
        rule.onNodeWithText("Импорт из Car Scanner").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun noImportLinkWithoutTheBackupFeature() {
        rule.setContent { SessionsScreen(listOf(imported), onOpen = {}, onDelete = {}, onImport = null) }
        rule.onNodeWithText("Импорт из Car Scanner").assertDoesNotExist()
        rule.onNodeWithText("импорт из Car Scanner", substring = true).assertExists()
    }

    private val imported = Session(8, "Car Scanner · 2026-01-02 12-00-00", 1_700_000_000_000, 1_700_000_060_000, SessionSource.IMPORT_CARSCANNER)
}

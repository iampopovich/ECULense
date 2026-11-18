package io.github.iampopovich.eculense.feature.analysis

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.iampopovich.eculense.core.analytics.Evidence
import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.data.AnalysisState
import io.github.iampopovich.eculense.core.data.SessionSummary
import io.github.iampopovich.eculense.core.data.StoredFinding
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.SessionSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private fun sf(id: Long, ruleId: String, severity: Severity, title: String, message: String = "m", evidence: List<Evidence> = emptyList()) =
        StoredFinding(id, 1, Finding(ruleId, severity, title, message, evidence, listOf(7)), 1_767_000_000_000)

    private val findings = listOf(
        sf(1, "voltage-low", Severity.OK, "Недозаряд АКБ", "Генератор заряжает"),
        sf(2, "fuel-trim-long", Severity.WARNING, "Долгосрочная коррекция топлива", "банк 1 +12,5 %"),
        sf(3, "maf-idle", Severity.NO_DATA, "Расход воздуха на холостом ходу", "Нет данных: укажите объём двигателя"),
        sf(4, "dtc-air-sensors", Severity.CRITICAL, "Датчики воздуха", "P0102×1"),
    )

    @Test
    fun problemsFirstWithSeverityLabelsAndSections() {
        val opened = mutableListOf<Long>()
        rule.setContent {
            AnalysisScreen(
                AnalysisUiState(loading = false, car = CarProfile(id = 1, name = "Тестовая"), findings = findings, analysis = AnalysisState.Done(1_767_000_000_000, 4_200, 74)),
                onRecompute = {},
                onOpen = { opened += it.id },
            )
        }
        rule.onNodeWithText("Тестовая").assertExists()
        rule.onNodeWithText("Требует внимания").assertExists()
        rule.onNodeWithText("Критично").assertExists()
        rule.onNodeWithText("Внимание").assertExists()
        rule.onNodeWithText("записей: 74", substring = true).assertExists()
        rule.onNodeWithText("4,2 с", substring = true).assertExists()
        rule.onNodeWithText("Долгосрочная коррекция топлива").performClick()
        assertEquals(listOf(2L), opened)
        rule.onRoot().performScrollToNode(hasText("В норме"))
        rule.onNodeWithText("Нет данных: укажите объём двигателя").assertExists()
        rule.onNodeWithText("Недозаряд АКБ").assertExists()
    }

    @Test
    fun progressDisablesRecompute() {
        var clicks = 0
        rule.setContent {
            AnalysisScreen(AnalysisUiState(loading = false, analysis = AnalysisState.Running(12, 74)), onRecompute = { clicks++ }, onOpen = {})
        }
        rule.onNodeWithText("Анализ записей: 12 из 74").assertExists()
        rule.onNodeWithText("Пересчитать").assertIsNotEnabled()
    }

    @Test
    fun emptyStateExplainsWhatToDo() {
        var clicks = 0
        rule.setContent { AnalysisScreen(AnalysisUiState(loading = false), onRecompute = { clicks++ }, onOpen = {}) }
        rule.onNodeWithText("Находок пока нет", substring = true).assertExists()
        rule.onNodeWithText("Пересчитать").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun findingDetailsShowEvidenceSessionsAndAskAi() {
        val asked = mutableListOf<Long>()
        val sessions = mutableListOf<Long>()
        val f = sf(2, "fuel-trim-long", Severity.WARNING, "Долгосрочная коррекция топлива", "банк 1 +12,5 %", listOf(Evidence("LTFT банк 1", "+12,5 % (замеров: 4)")))
        rule.setContent {
            FindingScreen(
                FindingUiState(loading = false, finding = f, sessions = listOf(SessionSummary(7, 1_767_000_000_000, null, SessionSource.LIVE, 3)), canAskAi = true),
                onAskAi = { asked += it.id },
                onOpenSession = { sessions += it },
            )
        }
        rule.onNodeWithText("Доказательства").assertExists()
        rule.onNodeWithText("+12,5 % (замеров: 4)").assertExists()
        rule.onNodeWithText("Записи (1)").assertExists()
        rule.onRoot().performScrollToNode(hasText("Спросить ИИ"))
        rule.onNodeWithText("Спросить ИИ").performClick()
        assertEquals(listOf(2L), asked)
    }

    @Test
    fun dtcEvidenceShowsDescriptionAndCauses() {
        val f = sf(3, "dtc-mixture", Severity.WARNING, "Смесь (P0171/P0174)", "P0171×3", listOf(Evidence("P0171", "3 раза")))
        rule.setContent {
            FindingScreen(
                FindingUiState(loading = false, finding = f, dtc = mapOf("P0171" to DtcNote("System Too Lean Bank 1", listOf("Air leak after the MAF")))),
                onAskAi = {},
                onOpenSession = {},
            )
        }
        rule.onNodeWithText("System Too Lean Bank 1").assertExists()
        rule.onNodeWithText("Частые причины: Air leak after the MAF").assertExists()
    }

    @Test
    fun noAskAiWithoutChatModule() {
        val f = sf(2, "fuel-trim-long", Severity.WARNING, "Долгосрочная коррекция топлива")
        rule.setContent { FindingScreen(FindingUiState(loading = false, finding = f, canAskAi = false), onAskAi = {}, onOpenSession = {}) }
        rule.onNodeWithText("Долгосрочная коррекция топлива").assertExists()
        rule.onNodeWithText("Спросить ИИ").assertDoesNotExist()
    }
}

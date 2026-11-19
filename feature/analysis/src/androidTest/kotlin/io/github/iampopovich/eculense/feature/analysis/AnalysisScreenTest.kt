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
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iampopovich.eculense.core.analytics.Evidence
import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.FindingArg
import io.github.iampopovich.eculense.core.analytics.FindingKey
import io.github.iampopovich.eculense.core.analytics.FindingText
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.analyticstext.AnalyticsText
import io.github.iampopovich.eculense.core.data.AnalysisState
import io.github.iampopovich.eculense.core.data.SessionSummary
import io.github.iampopovich.eculense.core.data.StoredFinding
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.SessionSource
import io.github.iampopovich.eculense.core.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AnalysisScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int, vararg args: Any) = ctx.getString(id, *args)
    private fun t(key: FindingKey, vararg args: FindingArg) = FindingText(key, args.toList())
    private fun sf(id: Long, ruleId: String, severity: Severity, title: FindingText, message: FindingText = FindingText.raw("m"), evidence: List<Evidence> = emptyList()) =
        StoredFinding(id, 1, Finding(ruleId, severity, title, message, evidence, listOf(7)), 1_767_000_000_000)

    private val findings = listOf(
        sf(1, "voltage-low", Severity.OK, t(FindingKey.TITLE_VOLTAGE_LOW), t(FindingKey.VOLTAGE_LOW_OK, FindingArg.Num(13.2))),
        sf(2, "fuel-trim-long", Severity.WARNING, t(FindingKey.TITLE_FUEL_TRIM_LONG), t(FindingKey.LEAN_NOTE)),
        sf(3, "maf-idle", Severity.NO_DATA, t(FindingKey.TITLE_MAF_IDLE), t(FindingKey.MAF_NO_VOLUME)),
        sf(4, "dtc-air-sensors", Severity.CRITICAL, t(FindingKey.TITLE_DTC_AIR_SENSORS), FindingText.raw("P0102×1")),
    )

    @Test
    fun problemsFirstWithSeverityLabelsAndSections() {
        val opened = mutableListOf<Long>()
        rule.setContent {
            AnalysisScreen(
                AnalysisUiState(loading = false, car = CarProfile(id = 1, name = "Test car"), findings = findings, analysis = AnalysisState.Done(1_767_000_000_000, 4_200, 74)),
                onRecompute = {},
                onOpen = { opened += it.id },
            )
        }
        rule.onNodeWithText("Test car").assertExists()
        rule.onNodeWithText(str(R.string.analysis_section_attention)).assertExists()
        rule.onNodeWithText(AnalyticsText.severity(ctx, Severity.CRITICAL)).assertExists()
        rule.onNodeWithText(AnalyticsText.severity(ctx, Severity.WARNING)).assertExists()
        rule.onNodeWithText(str(R.string.analysis_seconds, String.format(Locale.getDefault(), "%.1f", 4.2)), substring = true).assertExists()
        rule.onNodeWithText(AnalyticsText.title(ctx, findings[1].finding)).performClick()
        assertEquals(listOf(2L), opened)
        rule.onRoot().performScrollToNode(hasText(str(R.string.analysis_section_ok)))
        rule.onNodeWithText(AnalyticsText.message(ctx, findings[2].finding)).assertExists()
        rule.onNodeWithText(AnalyticsText.title(ctx, findings[0].finding)).assertExists()
    }

    @Test
    fun progressDisablesRecompute() {
        var clicks = 0
        rule.setContent {
            AnalysisScreen(AnalysisUiState(loading = false, analysis = AnalysisState.Running(12, 74)), onRecompute = { clicks++ }, onOpen = {})
        }
        rule.onNodeWithText(str(R.string.analysis_progress, 12, 74)).assertExists()
        rule.onNodeWithText(str(R.string.analysis_recompute)).assertIsNotEnabled()
    }

    @Test
    fun emptyStateExplainsWhatToDo() {
        var clicks = 0
        rule.setContent { AnalysisScreen(AnalysisUiState(loading = false), onRecompute = { clicks++ }, onOpen = {}) }
        rule.onNodeWithText(str(R.string.analysis_empty)).assertExists()
        rule.onNodeWithText(str(R.string.analysis_recompute)).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun findingDetailsShowEvidenceSessionsAndAskAi() {
        val asked = mutableListOf<Long>()
        val sessions = mutableListOf<Long>()
        val f = sf(
            2, "fuel-trim-long", Severity.WARNING, t(FindingKey.TITLE_FUEL_TRIM_LONG), t(FindingKey.LEAN_NOTE),
            listOf(Evidence(t(FindingKey.EVIDENCE_LTFT_BANK, FindingArg.Count(1)), t(FindingKey.VALUE_PERCENT, FindingArg.Num(12.5, signed = true)))),
        )
        rule.setContent {
            FindingScreen(
                FindingUiState(loading = false, finding = f, sessions = listOf(SessionSummary(7, 1_767_000_000_000, null, SessionSource.LIVE, 3)), canAskAi = true),
                onAskAi = { asked += it.id },
                onOpenSession = { sessions += it },
            )
        }
        rule.onNodeWithText(str(R.string.analysis_evidence)).assertExists()
        rule.onNodeWithText(AnalyticsText.value(ctx, f.finding.evidence.single())).assertExists()
        rule.onNodeWithText(str(R.string.analysis_recordings, 1)).assertExists()
        rule.onRoot().performScrollToNode(hasText(str(R.string.analysis_ask_ai)))
        rule.onNodeWithText(str(R.string.analysis_ask_ai)).performClick()
        assertEquals(listOf(2L), asked)
    }

    @Test
    fun dtcEvidenceShowsDescriptionAndCauses() {
        val f = sf(
            3, "dtc-mixture", Severity.WARNING, t(FindingKey.TITLE_DTC_MIXTURE, FindingArg.Raw("P0171/P0174")), FindingText.raw("P0171×3"),
            listOf(Evidence(FindingText.raw("P0171"), t(FindingKey.DTC_OCCURRENCES, FindingArg.Count(3), FindingArg.DateRange(1, 1)))),
        )
        rule.setContent {
            FindingScreen(
                FindingUiState(loading = false, finding = f, dtc = mapOf("P0171" to DtcNote(UiText.Raw("System Too Lean Bank 1"), listOf("Air leak after the MAF")))),
                onAskAi = {},
                onOpenSession = {},
            )
        }
        rule.onNodeWithText("System Too Lean Bank 1").assertExists()
        rule.onNodeWithText(str(R.string.analysis_common_causes, "Air leak after the MAF")).assertExists()
    }

    @Test
    fun noAskAiWithoutChatModule() {
        val f = sf(2, "fuel-trim-long", Severity.WARNING, t(FindingKey.TITLE_FUEL_TRIM_LONG))
        rule.setContent { FindingScreen(FindingUiState(loading = false, finding = f, canAskAi = false), onAskAi = {}, onOpenSession = {}) }
        rule.onNodeWithText(AnalyticsText.title(ctx, f.finding)).assertExists()
        rule.onNodeWithText(str(R.string.analysis_ask_ai)).assertDoesNotExist()
    }

    @Test
    fun askAiPromptCarriesTheComputedNumbersInTheAppLanguage() {
        val f = sf(
            1, "fuel-trim-long", Severity.WARNING, t(FindingKey.TITLE_FUEL_TRIM_LONG), t(FindingKey.LEAN_NOTE),
            listOf(Evidence(t(FindingKey.EVIDENCE_LTFT_BANK, FindingArg.Count(1)), t(FindingKey.VALUE_PERCENT, FindingArg.Num(12.5, signed = true)))),
        )
        val text = askAiPrompt(ctx, f)
        val title = AnalyticsText.title(ctx, f.finding)
        assertTrue(text, text.startsWith(str(R.string.analysis_ask_ai_prompt, title, AnalyticsText.severity(ctx, Severity.WARNING), AnalyticsText.message(ctx, f.finding))))
        assertTrue(text, text.contains("${AnalyticsText.label(ctx, f.finding.evidence[0])} — ${AnalyticsText.value(ctx, f.finding.evidence[0])}"))
        assertTrue(text, text.endsWith(str(R.string.analysis_ask_ai_question)))
    }
}

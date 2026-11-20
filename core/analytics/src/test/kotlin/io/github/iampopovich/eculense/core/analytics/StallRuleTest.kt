package io.github.iampopovich.eculense.core.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class StallRuleTest {
    private fun input(vararg stalls: Pair<Long, Long>) = AnalysisInput(
        car = null, sessions = emptyList(), dtcEvents = emptyList(), nowMs = 0,
        events = stalls.map { (s, t) -> TripEventFact(s, t, TripEventType.STALL) } + TripEventFact(1, 5, TripEventType.USER_MARK),
    )

    @Test
    fun `no stalls and no recordings is no data, marks do not count`() {
        assertEquals(Severity.NO_DATA, StallRule.evaluate(input()).single().severity)
    }

    @Test
    fun `one stall is info, two are a warning, four are critical`() {
        assertEquals(Severity.INFO, StallRule.evaluate(input(1L to 10L)).single().severity)
        val two = StallRule.evaluate(input(1L to 10L, 2L to 20L)).single()
        assertEquals(Severity.WARNING, two.severity)
        assertEquals(listOf(1L, 2L), two.sessionIds)
        assertEquals("The engine stalled while running (the ECU kept answering): 2 in 2 recordings.", FindingEnglish.render(two.message.first()))
        assertEquals(Severity.CRITICAL, StallRule.evaluate(input(1L to 1L, 1L to 2L, 1L to 3L, 2L to 4L)).single().severity)
    }

    @Test
    fun `stall rule is part of the engine`() {
        assertEquals(true, StallRule in AnalyticsEngine.RULES)
    }
}

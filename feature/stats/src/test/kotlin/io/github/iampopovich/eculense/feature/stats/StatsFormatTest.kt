package io.github.iampopovich.eculense.feature.stats

import io.github.iampopovich.eculense.core.model.StatsPeriod
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsFormatTest {
    @Test
    fun emptyTitleNamesThePeriodOrSaysNoTripsAtAll() {
        assertEquals("Поездок пока нет", StatsFormat.emptyTitle(StatsPeriod.WEEK, totalTrips = 0))
        assertEquals("Поездок пока нет", StatsFormat.emptyTitle(StatsPeriod.ALL, totalTrips = 0))
        assertEquals("Нет поездок за неделю", StatsFormat.emptyTitle(StatsPeriod.WEEK, totalTrips = 3))
        assertEquals("Нет поездок за месяц", StatsFormat.emptyTitle(StatsPeriod.MONTH, totalTrips = 3))
        assertEquals("Нет поездок за год", StatsFormat.emptyTitle(StatsPeriod.YEAR, totalTrips = 3))
        assertEquals("Нет поездок за всё время", StatsFormat.emptyTitle(StatsPeriod.ALL, totalTrips = 3))
    }
}

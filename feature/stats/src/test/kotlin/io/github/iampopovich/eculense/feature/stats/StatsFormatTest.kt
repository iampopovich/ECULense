package io.github.iampopovich.eculense.feature.stats

import io.github.iampopovich.eculense.core.model.StatsPeriod
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsFormatTest {
    @Test
    fun emptyTitleNamesThePeriodOrSaysNoTripsAtAll() {
        assertEquals(R.string.stats_no_trips_at_all, StatsFormat.emptyTitle(StatsPeriod.WEEK, totalTrips = 0))
        assertEquals(R.string.stats_no_trips_at_all, StatsFormat.emptyTitle(StatsPeriod.ALL, totalTrips = 0))
        assertEquals(R.string.stats_no_trips_week, StatsFormat.emptyTitle(StatsPeriod.WEEK, totalTrips = 3))
        assertEquals(R.string.stats_no_trips_month, StatsFormat.emptyTitle(StatsPeriod.MONTH, totalTrips = 3))
        assertEquals(R.string.stats_no_trips_year, StatsFormat.emptyTitle(StatsPeriod.YEAR, totalTrips = 3))
        assertEquals(R.string.stats_no_trips_all, StatsFormat.emptyTitle(StatsPeriod.ALL, totalTrips = 3))
    }
}

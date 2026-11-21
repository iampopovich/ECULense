package io.github.iampopovich.eculense.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinksTest {
    private val adapter = HubEntry(Hub.SETTINGS, ConnectDestination, titleRes = 1, subtitleRes = 0, icon = 0, order = 10)
    private val analysis = HubEntry(Hub.PITSTOP, AnalysisDestination, titleRes = 2, subtitleRes = 0, icon = 0, order = 10)
    private val tabs = listOf(DashboardDestination, StatsDestination, PitstopDestination, SettingsDestination)

    @Test
    fun `connect link opens the adapter screen on top of Settings`() {
        assertEquals(ConnectDestination, DeepLinks.destination(DeepLinks.CONNECT))
        assertEquals(listOf(SettingsDestination, ConnectDestination), DeepLinks.backStack(ConnectDestination, listOf(adapter, analysis), tabs))
    }

    @Test
    fun `pit-stop links keep landing on top of Pit-stop`() {
        assertEquals(listOf(PitstopDestination, AnalysisDestination), DeepLinks.backStack(AnalysisDestination, listOf(adapter, analysis), tabs))
    }

    @Test
    fun `missing feature or tab gives no back stack`() {
        assertNull(DeepLinks.backStack(ConnectDestination, listOf(analysis), tabs))
        assertNull(DeepLinks.backStack(ConnectDestination, listOf(adapter), tabs - SettingsDestination))
        assertNull(DeepLinks.destination("unknown"))
    }
}

package io.github.iampopovich.eculense.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class HubEntriesTest {
    @Test
    fun `forHub keeps only the requested hub sorted by order`() {
        // Title ids stand in for string resources: the hub resolves them, the registry only orders entries.
        val entries = listOf(
            HubEntry(Hub.SETTINGS, ConnectDestination, titleRes = 1, subtitleRes = 0, icon = 0, order = 20),
            HubEntry(Hub.PITSTOP, DtcDestination, titleRes = 2, subtitleRes = 0, icon = 0, order = 10),
            HubEntry(Hub.SETTINGS, AiSettingsDestination, titleRes = 3, subtitleRes = 0, icon = 0, order = 10),
        )
        assertEquals(listOf(3, 1), entries.forHub(Hub.SETTINGS).map { it.titleRes })
        assertEquals(listOf(2), entries.forHub(Hub.PITSTOP).map { it.titleRes })
    }
}

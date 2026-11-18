package io.github.iampopovich.eculense.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class HubEntriesTest {
    @Test
    fun `forHub keeps only the requested hub sorted by order`() {
        val entries = listOf(
            HubEntry(Hub.SETTINGS, ConnectDestination, "Адаптер", "", 0, order = 20),
            HubEntry(Hub.PITSTOP, DtcDestination, "Ошибки", "", 0, order = 10),
            HubEntry(Hub.SETTINGS, AiSettingsDestination, "ИИ", "", 0, order = 10),
        )
        assertEquals(listOf("ИИ", "Адаптер"), entries.forHub(Hub.SETTINGS).map { it.title })
        assertEquals(listOf("Ошибки"), entries.forHub(Hub.PITSTOP).map { it.title })
    }
}

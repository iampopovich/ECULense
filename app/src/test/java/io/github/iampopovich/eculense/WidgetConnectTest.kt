package io.github.iampopovich.eculense

import io.github.iampopovich.eculense.core.navigation.DeepLinks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetConnectTest {
    @Test
    fun `remembered adapter connects without opening a screen`() = runBlocking {
        val opened = mutableListOf<String>()
        widgetConnect(connectLast = { true }) { opened += it }
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun `no remembered adapter opens the adapter screen`() = runBlocking {
        val opened = mutableListOf<String>()
        widgetConnect(connectLast = { false }) { opened += it }
        assertEquals(listOf(DeepLinks.CONNECT), opened)
    }
}

package io.github.iampopovich.eculense.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardSelectionTest {
    @Test
    fun `defaults are limited to what the car supports, fuel rate may be estimated from MAF`() {
        assertEquals(listOf(0x0C, 0x0D, 0x05), DashboardSelection.resolve(null, setOf(0x05, 0x0C, 0x0D)))
        assertEquals(listOf(0x5E, 0x0C), DashboardSelection.resolve(listOf(0x5E, 0x0C, 0x99), setOf(0x0C, 0x10)))
        assertEquals(listOf(0x0C), DashboardSelection.resolve(listOf(0x5E, 0x0C), setOf(0x0C)))
    }

    @Test
    fun `edits toggle and move without text input`() {
        val s = listOf(1, 2, 3)
        assertEquals(listOf(1, 3), DashboardSelection.toggle(s, 2))
        assertEquals(listOf(1, 2, 3, 4), DashboardSelection.toggle(s, 4))
        assertEquals(listOf(2, 1, 3), DashboardSelection.moveUp(s, 2))
        assertEquals(s, DashboardSelection.moveUp(s, 1))
        assertEquals(listOf(1, 3, 2), DashboardSelection.moveDown(s, 2))
        assertEquals(s, DashboardSelection.moveDown(s, 3))
    }

    @Test
    fun `selection round-trips through its stored form`() {
        val codes = listOf(0x0C, 0x5E, 0x05)
        assertEquals(codes, DashboardSelection.decode(DashboardSelection.encode(codes)))
        assertEquals(emptyList<Int>(), DashboardSelection.decode(""))
    }

    @Test
    fun `driving tile count is clamped`() = runTest {
        val prefs = InMemoryDashboardPrefs()
        assertNull(prefs.selection.first())
        prefs.setDrivingTiles(9)
        assertEquals(6, prefs.drivingTiles.first())
        prefs.setDrivingTiles(1)
        assertEquals(2, prefs.drivingTiles.first())
    }
}

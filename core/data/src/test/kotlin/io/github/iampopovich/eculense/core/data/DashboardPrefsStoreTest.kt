package io.github.iampopovich.eculense.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** DataStoreDashboardPrefs on a real preferences file: per-car PID and extended (OBDb) selections, clamped tiles. */
class DashboardPrefsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `selections are kept per car, extended ones removed when emptied, global switches have defaults`() = runTest {
        val car = MutableStateFlow<Long?>(1L)
        val prefs = DataStoreDashboardPrefs(PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { File(tmp.root, "dashboard.preferences_pb") }), car)
        assertNull(prefs.selection.first()) // nothing chosen yet: the screen uses the defaults
        assertTrue(prefs.keepScreenOn.first())
        assertFalse(prefs.hudMirror.first())

        prefs.setSelection(listOf(0x0C, 0x05))
        prefs.setExtendedSelection(listOf("obdb:TEST_OIL_T"))
        car.value = 2L
        assertNull(prefs.selection.first())
        assertTrue(prefs.extendedSelection.first().isEmpty())
        prefs.setSelection(listOf(0x0D))
        car.value = 1L
        assertEquals(listOf(0x0C, 0x05), prefs.selection.first())
        assertEquals(listOf("obdb:TEST_OIL_T"), prefs.extendedSelection.first())
        prefs.setExtendedSelection(emptyList())
        assertTrue(prefs.extendedSelection.first().isEmpty())

        car.value = null // no car yet: its own slot
        prefs.setSelection(listOf(0x42))
        assertEquals(listOf(0x42), prefs.selection.first())
        car.value = 2L
        assertEquals(listOf(0x0D), prefs.selection.first())

        prefs.setDrivingTiles(99)
        assertEquals(DashboardSelection.MAX_DRIVING_TILES, prefs.drivingTiles.first())
        prefs.setDrivingTiles(-1)
        assertEquals(DashboardSelection.MIN_DRIVING_TILES, prefs.drivingTiles.first())
        prefs.setKeepScreenOn(false)
        prefs.setHudMirror(true)
        assertFalse(prefs.keepScreenOn.first())
        assertTrue(prefs.hudMirror.first())
    }
}
